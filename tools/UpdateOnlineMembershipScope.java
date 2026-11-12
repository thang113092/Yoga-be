import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Apply V14 (access scope of plans and passes: STUDIO / ONLINE / HYBRID) to the configured application database
 * in one transaction. Requires V13. Never prints credentials or personal data.
 * Run from backend/: java -cp postgresql.jar tools/UpdateOnlineMembershipScope.java --check|--apply
 */
class UpdateOnlineMembershipScope {
 static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 static final String MIGRATION="V14__online_membership_scope.sql";
 /** Existing rows must stay identical apart from the new access_scope column. */
 static final List<String> TABLES=List.of("users","branches","rooms","membership_plans","memberships","bookings","waitlists",
   "class_schedules","attendance_records","orders","order_items","payments","user_branches");

 static Connection connect() throws Exception {
  var settings=new Properties();
  try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) { settings.load(reader); }
  String url=settings.getProperty("YOGA_DB_JDBC_URL"), user=settings.getProperty("YOGA_DB_USERNAME");
  if(url==null||!url.contains("pooler.supabase.com")||!"postgres.cagwkxiccmayrtajwdtc".equals(user))
   throw new IllegalStateException("Configured database does not match the application target");
  var credentials=new Properties();credentials.setProperty("user",user);credentials.setProperty("password",settings.getProperty("YOGA_DB_PASSWORD"));
  credentials.setProperty("connectTimeout","15");credentials.setProperty("socketTimeout","180");
  return DriverManager.getConnection(url,credentials);
 }

 static void print(Connection db,String q) throws SQLException {
  try(var st=db.createStatement();var r=st.executeQuery(q)) {
   var md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++)System.out.print(md.getColumnLabel(i)+(i==md.getColumnCount()?"\n":" | "));
   while(r.next()){for(int i=1;i<=md.getColumnCount();i++)System.out.print(r.getString(i)+(i==md.getColumnCount()?"\n":" | "));}
  }
 }

 static final String READY="""
   SELECT to_regprocedure('yoga.add_branch_online_room()') IS NOT NULL
     AND to_regprocedure('yoga.guard_live_host_overlap()') IS NOT NULL
     AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='yoga' AND table_name='membership_plans' AND column_name='access_scope')
     AND to_regprocedure('yoga.guard_membership_scope()') IS NULL
     AND NOT EXISTS(SELECT 1 FROM pg_tables WHERE tablename='flyway_schema_history')""";

 static void inspect(Connection db) throws Exception {
  print(db,"SELECT version() AS database_version");
  print(db,"SELECT ("+READY+") AS ready_for_v14");
  print(db,"SELECT count(*) AS plans,count(*) FILTER (WHERE is_active) AS active_plans FROM yoga.membership_plans");
  print(db,"SELECT status,count(*) AS memberships FROM yoga.memberships GROUP BY status ORDER BY status");
 }

 static String fingerprint(Connection db) throws SQLException {
  StringBuilder all=new StringBuilder();
  for(String table:TABLES) {
   String q="SELECT count(*)::text||':'||md5(COALESCE(jsonb_agg(to_jsonb(t)-'access_scope' ORDER BY id)::text,'[]')) FROM yoga."+table+" t";
   try(var s=db.createStatement();var r=s.executeQuery(q)){r.next();all.append(table).append('|').append(r.getString(1)).append('\n');}
  }
  return all.toString();
 }

 static void apply(Connection db) throws Exception {
  db.setAutoCommit(false);
  try {
   try(var st=db.createStatement()) {
    st.execute("SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='120s'; SET LOCAL search_path=yoga,public; SELECT pg_advisory_xact_lock(hashtextextended('yoga-online-membership-v14',0)); LOCK TABLE yoga.membership_plans,yoga.memberships,yoga.bookings,yoga.waitlists IN SHARE ROW EXCLUSIVE MODE");
    try(var r=st.executeQuery(READY)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Database is not at V13 or V14 is already applied; no changes committed");}
   }
   String before=fingerprint(db);
   Path backup=ROOT.resolve("database/backups/online-membership-v14-"+System.currentTimeMillis());Files.createDirectories(backup);
   StringBuilder definitions=new StringBuilder("-- membership_exchange_quote before V14, for recovery; contains no application row data.\n");
   try(var s=db.createStatement();var r=s.executeQuery("SELECT pg_get_functiondef('yoga.membership_exchange_quote(uuid,uuid,uuid,uuid)'::regprocedure)")) {
    while(r.next())definitions.append(r.getString(1)).append(";\n");
   }
   Files.writeString(backup.resolve("before.sql"),definitions);
   Files.writeString(backup.resolve("fingerprint-before.txt"),before);
   byte[] bytes=Files.readAllBytes(ROOT.resolve("database/migrations/"+MIGRATION));
   Files.writeString(backup.resolve("migrations.sha256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))+" *database/migrations/"+MIGRATION+"\n");
   try(var s=db.createStatement()){s.execute(new String(bytes,StandardCharsets.UTF_8));}
   String after=fingerprint(db);
   if(!before.equals(after))throw new IllegalStateException("Existing application records changed; rolling back");
   String verify="""
     SELECT NOT EXISTS(SELECT 1 FROM yoga.membership_plans WHERE access_scope<>'STUDIO')
       AND NOT EXISTS(SELECT 1 FROM yoga.memberships WHERE access_scope<>'STUDIO')
       AND EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='yoga.memberships'::regclass AND tgname='snapshot_membership_scope')
       AND (SELECT count(*) FROM pg_trigger WHERE tgname='guard_membership_scope')=3
       AND (SELECT bool_and(p.prosecdef) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='yoga'
            AND p.proname IN ('snapshot_membership_scope','guard_membership_scope','membership_exchange_quote'))""";
   try(var s=db.createStatement();var r=s.executeQuery(verify)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Upgrade verification failed; rolling back");}
   Files.writeString(backup.resolve("fingerprint-after.txt"),after);
   db.commit();
   System.out.println("V14_COMMITTED: plans and passes have access_scope STUDIO; existing rows unchanged");
   System.out.println("RECOVERY_FILES="+backup);
  } catch(Exception e){db.rollback();throw e;}
 }

 public static void main(String[] args) throws Exception {
  if(args.length!=1||!Set.of("--check","--apply").contains(args[0]))throw new IllegalArgumentException("Use --check or --apply");
  try(var db=connect()){if(args[0].equals("--check")){db.setReadOnly(true);inspect(db);}else apply(db);}
 }
}
