import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Apply V11 (when missing) and V12 (online live classes) to the configured application database in one transaction.
 * Never prints credentials or personal data. Run from backend/: java -cp postgresql.jar tools/UpdateOnlineClasses.java --check|--apply
 */
class UpdateOnlineClasses {
 static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 static final String MIGRATION="V12__online_live_classes.sql";
 static final String V11="V11__block_cancel_while_reconciling.sql";
 static final String SEEDED="('b0000000-0000-0000-0000-0000000000f1','c0000000-0000-0000-0000-0000000000f1')";
 /** Existing rows must stay identical; the new columns and the two seeded rows are left out of the comparison. */
 static final List<String> TABLES=List.of("users","branches","rooms","memberships","bookings","waitlists","class_schedules",
   "attendance_records","orders","order_items","payments","user_branches");

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
   SELECT to_regclass('yoga.online_payment_transactions') IS NOT NULL
     AND to_regclass('yoga.online_meetings') IS NULL AND to_regclass('yoga.integration_events') IS NULL
     AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='yoga' AND table_name='class_schedules' AND column_name='delivery_mode')
     AND NOT EXISTS(SELECT 1 FROM yoga.branches WHERE code='ONLINE' OR id='b0000000-0000-0000-0000-0000000000f1')
     AND NOT EXISTS(SELECT 1 FROM yoga.rooms WHERE id='c0000000-0000-0000-0000-0000000000f1')
     AND NOT EXISTS(SELECT 1 FROM pg_tables WHERE tablename='flyway_schema_history')""";

 static void inspect(Connection db) throws Exception {
  print(db,"SELECT version() AS database_version");
  print(db,"SELECT ("+READY+") AS ready_for_v12");
  print(db,"SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='yoga_app') AS has_yoga_app");
  print(db,"SELECT 'branches' AS table_name,count(*) AS rows FROM yoga.branches UNION ALL SELECT 'rooms',count(*) FROM yoga.rooms UNION ALL SELECT 'class_schedules',count(*) FROM yoga.class_schedules UNION ALL SELECT 'bookings',count(*) FROM yoga.bookings UNION ALL SELECT 'attendance_records',count(*) FROM yoga.attendance_records");
 }

 static String fingerprint(Connection db) throws SQLException {
  StringBuilder all=new StringBuilder();
  for(String table:TABLES) {
   String q="SELECT count(*)::text||':'||md5(COALESCE(jsonb_agg(to_jsonb(t)-ARRAY['is_virtual','room_type','live_host_account','delivery_mode'] ORDER BY id)::text,'[]')) FROM yoga."+table+" t WHERE id NOT IN "+SEEDED;
   try(var s=db.createStatement();var r=s.executeQuery(q)){r.next();all.append(table).append('|').append(r.getString(1)).append('\n');}
  }
  return all.toString();
 }

 static void apply(Connection db) throws Exception {
  db.setAutoCommit(false);
  try {
   try(var st=db.createStatement()) {
    st.execute("SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='120s'; SET LOCAL search_path=yoga,public; SELECT pg_advisory_xact_lock(hashtextextended('yoga-online-classes-v12',0)); LOCK TABLE yoga.branches,yoga.rooms,yoga.class_schedules,yoga.bookings,yoga.waitlists,yoga.attendance_records IN SHARE ROW EXCLUSIVE MODE");
    try(var r=st.executeQuery(READY)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Database is not at V10/V11 or V12 is partially applied; no changes committed");}
   }
   String before=fingerprint(db);
   Path backup=ROOT.resolve("database/backups/online-classes-"+System.currentTimeMillis());Files.createDirectories(backup);
   StringBuilder definitions=new StringBuilder("-- Function definitions replaced by V12, for recovery; contains no application row data.\n");
   try(var s=db.createStatement();var r=s.executeQuery("SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='yoga' AND p.proname IN ('validate_booking','validate_checkin','validate_waitlist') ORDER BY p.proname")) {
    while(r.next())definitions.append(r.getString(1)).append(";\n\n");
   }
   Files.writeString(backup.resolve("before.sql"),definitions);
   Files.writeString(backup.resolve("fingerprint-before.txt"),before);
   boolean needsV11;
   try(var s=db.createStatement();var r=s.executeQuery("SELECT NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='yoga.orders'::regclass AND tgname='guard_order_cancel_reconciliation')")){r.next();needsV11=r.getBoolean(1);}
   StringBuilder manifest=new StringBuilder();
   for(String file:needsV11?List.of(V11,MIGRATION):List.of(MIGRATION)) {
    byte[] bytes=Files.readAllBytes(ROOT.resolve("database/migrations/"+file));
    manifest.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).append(" *database/migrations/").append(file).append('\n');
    try(var s=db.createStatement()){s.execute(new String(bytes,StandardCharsets.UTF_8));}
   }
   Files.writeString(backup.resolve("migrations.sha256"),manifest);
   try(var s=db.createStatement()){s.execute(Files.readString(ROOT.resolve("database/scripts/provision_runtime.sql")));}
   String after=fingerprint(db);
   if(!before.equals(after))throw new IllegalStateException("Existing application records changed; rolling back");
   String verify="""
     SELECT (SELECT count(*) FROM yoga.branches WHERE is_virtual)=1
       AND (SELECT room_type FROM yoga.rooms WHERE id='c0000000-0000-0000-0000-0000000000f1')='VIRTUAL'
       AND NOT EXISTS(SELECT 1 FROM yoga.rooms WHERE room_type='VIRTUAL' AND id<>'c0000000-0000-0000-0000-0000000000f1')
       AND NOT EXISTS(SELECT 1 FROM yoga.class_schedules WHERE delivery_mode<>'IN_PERSON')
       AND (SELECT count(*) FROM information_schema.tables WHERE table_schema='yoga' AND table_name IN ('online_meetings','online_meeting_registrants','integration_events'))=3
       AND (SELECT bool_and(p.prosecdef) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='yoga'
            AND p.proname IN ('validate_booking','validate_checkin','validate_waitlist','queue_online_meeting','queue_online_registrant','validate_online_checkin'))
       AND has_table_privilege('yoga_app','yoga.integration_events','INSERT')
       AND has_column_privilege('yoga_app','yoga.online_meeting_registrants','join_url','UPDATE')
       AND NOT has_table_privilege('yoga_app','yoga.online_meetings','INSERT')
       AND EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='yoga.orders'::regclass AND tgname='guard_order_cancel_reconciliation')""";
   try(var s=db.createStatement();var r=s.executeQuery(verify)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Upgrade verification failed; rolling back");}
   Files.writeString(backup.resolve("fingerprint-after.txt"),after);
   db.commit();
   System.out.println((needsV11?"V11+V12":"V12")+"_COMMITTED: runtime grants applied; existing rows unchanged");
   System.out.println("RECOVERY_FILES="+backup);
  } catch(Exception e){db.rollback();throw e;}
 }

 public static void main(String[] args) throws Exception {
  if(args.length!=1||!Set.of("--check","--apply").contains(args[0]))throw new IllegalArgumentException("Use --check or --apply");
  try(var db=connect()){if(args[0].equals("--check")){db.setReadOnly(true);inspect(db);}else apply(db);}
 }
}
