import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Apply V13 (online classes per studio branch) to the configured application database in one transaction.
 * Requires V12. Never prints credentials or personal data.
 * Run from backend/: java -cp postgresql.jar tools/UpdateOnlineClassesPerBranch.java --check|--apply
 */
class UpdateOnlineClassesPerBranch {
 static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 static final String MIGRATION="V13__online_classes_per_branch.sql";
 /** Existing rows must stay identical. Virtual rooms and the virtual branch are what V13 adds or retires. */
 static final List<String> TABLES=List.of("users","branches","rooms","memberships","bookings","waitlists","class_schedules",
   "attendance_records","orders","order_items","payments","user_branches","online_meetings","online_meeting_registrants");

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
   SELECT to_regclass('yoga.online_meetings') IS NOT NULL
     AND EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='yoga.class_schedules'::regclass AND tgname='queue_online_meeting')
     AND to_regprocedure('yoga.add_branch_online_room()') IS NULL
     AND to_regprocedure('yoga.guard_live_host_overlap()') IS NULL
     AND NOT EXISTS(SELECT 1 FROM pg_tables WHERE tablename='flyway_schema_history')""";

 static void inspect(Connection db) throws Exception {
  print(db,"SELECT version() AS database_version");
  print(db,"SELECT ("+READY+") AS ready_for_v13");
  print(db,"SELECT b.code,b.is_virtual,b.is_active,(SELECT count(*) FROM yoga.rooms r WHERE r.branch_id=b.id AND r.room_type='VIRTUAL') AS online_rooms,(SELECT count(*) FROM yoga.class_schedules s WHERE s.branch_id=b.id AND s.delivery_mode='ONLINE') AS online_classes FROM yoga.branches b ORDER BY b.is_virtual,b.code");
  print(db,"SELECT count(*) AS rooms_named_phong_online_physical FROM yoga.rooms WHERE name='Phòng online' AND room_type='PHYSICAL'");
 }

 static String fingerprint(Connection db) throws SQLException {
  StringBuilder all=new StringBuilder();
  for(String table:TABLES) {
   String q="SELECT count(*)::text||':'||md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]')) FROM yoga."+table+" t"
     +" WHERE (to_jsonb(t)->>'room_type') IS DISTINCT FROM 'VIRTUAL' AND (to_jsonb(t)->>'is_virtual') IS DISTINCT FROM 'true'";
   try(var s=db.createStatement();var r=s.executeQuery(q)){r.next();all.append(table).append('|').append(r.getString(1)).append('\n');}
  }
  return all.toString();
 }

 static void apply(Connection db) throws Exception {
  db.setAutoCommit(false);
  try {
   try(var st=db.createStatement()) {
    st.execute("SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='120s'; SET LOCAL search_path=yoga,public; SELECT pg_advisory_xact_lock(hashtextextended('yoga-online-classes-v13',0)); LOCK TABLE yoga.branches,yoga.rooms,yoga.class_schedules IN SHARE ROW EXCLUSIVE MODE");
    try(var r=st.executeQuery(READY)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Database is not at V12 or V13 is already applied; no changes committed");}
   }
   String before=fingerprint(db);
   Path backup=ROOT.resolve("database/backups/online-classes-v13-"+System.currentTimeMillis());Files.createDirectories(backup);
   StringBuilder definitions=new StringBuilder("-- derive_room_type before V13, for recovery; contains no application row data.\n");
   try(var s=db.createStatement();var r=s.executeQuery("SELECT pg_get_functiondef('yoga.derive_room_type()'::regprocedure)")) {
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
     SELECT NOT EXISTS(SELECT 1 FROM yoga.branches b WHERE NOT b.is_virtual
              AND NOT EXISTS(SELECT 1 FROM yoga.rooms r WHERE r.branch_id=b.id AND r.room_type='VIRTUAL'))
       AND EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='yoga.branches'::regclass AND tgname='add_branch_online_room')
       AND EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='yoga.class_schedules'::regclass AND tgname='guard_live_host_overlap')
       AND (SELECT bool_and(p.prosecdef) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='yoga'
            AND p.proname IN ('derive_room_type','add_branch_online_room','guard_live_host_overlap'))""";
   try(var s=db.createStatement();var r=s.executeQuery(verify)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Upgrade verification failed; rolling back");}
   Files.writeString(backup.resolve("fingerprint-after.txt"),after);
   db.commit();
   System.out.println("V13_COMMITTED: every studio branch has an online room; existing rows unchanged");
   System.out.println("RECOVERY_FILES="+backup);
  } catch(Exception e){db.rollback();throw e;}
 }

 public static void main(String[] args) throws Exception {
  if(args.length!=1||!Set.of("--check","--apply").contains(args[0]))throw new IllegalArgumentException("Use --check or --apply");
  try(var db=connect()){if(args[0].equals("--check")){db.setReadOnly(true);inspect(db);}else apply(db);}
 }
}
