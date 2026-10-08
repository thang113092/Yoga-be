import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;

/** Upgrade only the configured application database; never prints credentials or personal data. */
class UpdateCourses {
 static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 static Connection connect() throws Exception {
  var settings=new Properties();
  try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) { settings.load(reader); }
  String url=settings.getProperty("YOGA_DB_JDBC_URL"), user=settings.getProperty("YOGA_DB_USERNAME");
  if(url==null||!url.contains("aws-0-ap-southeast-1.pooler.supabase.com")||!"postgres.cagwkxiccmayrtajwdtc".equals(user))
   throw new IllegalStateException("Configured database does not match the application target");
  var credentials=new Properties();credentials.setProperty("user",user);credentials.setProperty("password",settings.getProperty("YOGA_DB_PASSWORD"));
  credentials.setProperty("connectTimeout","15");credentials.setProperty("socketTimeout","120");
  return DriverManager.getConnection(url,credentials);
 }
 static void inspect(Connection db) throws Exception {
  String[] queries={
   "SELECT version() AS database_version",
   "SELECT table_name FROM information_schema.tables WHERE table_schema='yoga' AND table_name IN ('courses','course_classes','course_enrollments') ORDER BY table_name",
   "SELECT table_name,column_name,is_nullable FROM information_schema.columns WHERE table_schema='yoga' AND column_name IN ('course_class_id','course_enrollment_id','session_number','replaces_membership_id') ORDER BY table_name,column_name",
   "SELECT p.proname,p.prosecdef AS security_definer FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='yoga' AND p.proname IN ('membership_exchange_quote','validate_schedule','validate_booking','validate_payment','apply_payment','validate_course_class','validate_course_enrollment') ORDER BY p.proname",
   "SELECT schemaname,tablename FROM pg_tables WHERE tablename='flyway_schema_history'",
   "SELECT rolname FROM pg_roles WHERE rolname='yoga_app'",
   "SELECT 'memberships' AS table_name,count(*) AS rows FROM yoga.memberships UNION ALL SELECT 'bookings',count(*) FROM yoga.bookings UNION ALL SELECT 'class_schedules',count(*) FROM yoga.class_schedules UNION ALL SELECT 'orders',count(*) FROM yoga.orders UNION ALL SELECT 'payments',count(*) FROM yoga.payments"
  };
  for(String q:queries) try(var st=db.createStatement();var r=st.executeQuery(q)) {
   var md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++)System.out.print(md.getColumnLabel(i)+(i==md.getColumnCount()?"\n":" | "));
   while(r.next()){for(int i=1;i<=md.getColumnCount();i++)System.out.print(r.getString(i)+(i==md.getColumnCount()?"\n":" | "));}
  }
 }
 static String fingerprint(Connection db) throws SQLException {
  StringBuilder all=new StringBuilder();
  for(String table:List.of("memberships","bookings","class_schedules","attendance_records","orders","order_items","payments")) {
   String q="SELECT count(*)::text||':'||md5(COALESCE(jsonb_agg(to_jsonb(t)-ARRAY['course_class_id','course_enrollment_id','session_number','session_title'] ORDER BY id)::text,'[]')) FROM yoga."+table+" t";
   try(var s=db.createStatement();var r=s.executeQuery(q)){r.next();all.append(table).append(':').append(r.getString(1)).append('\n');}
  }
  return all.toString();
 }
 static void apply(Connection db) throws Exception {
  db.setAutoCommit(false);
  try {
   try(var st=db.createStatement()) {
    st.execute("SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='90s'; SET LOCAL search_path=yoga,public; SELECT pg_advisory_xact_lock(hashtextextended('yoga-course-upgrade-v6-v8',0)); LOCK TABLE yoga.memberships,yoga.bookings,yoga.class_schedules,yoga.attendance_records,yoga.orders,yoga.order_items,yoga.payments IN SHARE ROW EXCLUSIVE MODE");
    try(var r=st.executeQuery("SELECT to_regclass('yoga.courses') IS NULL AND to_regclass('yoga.course_classes') IS NULL AND to_regclass('yoga.course_enrollments') IS NULL AND to_regprocedure('yoga.membership_exchange_quote(uuid,uuid,uuid,uuid)') IS NOT NULL AND EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='yoga.memberships'::regclass AND tgname='guard_membership_exchange')")) {
     r.next();if(!r.getBoolean(1))throw new IllegalStateException("Unexpected or partially upgraded schema; no changes committed");
    }
    try(var r=st.executeQuery("SELECT count(*) FROM pg_tables WHERE tablename='flyway_schema_history'")) {r.next();if(r.getInt(1)>0)throw new IllegalStateException("Flyway history exists; use the migration pipeline to preserve history");}
   }
   String before=fingerprint(db);
   Path backup=ROOT.resolve("database/backups/course-upgrade-"+System.currentTimeMillis());Files.createDirectories(backup);
   StringBuilder definitions=new StringBuilder("-- Previous function definitions for recovery; contains no application row data.\nSET search_path=yoga,public;\n");
   try(var s=db.createStatement();var r=s.executeQuery("SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='yoga' ORDER BY p.proname,p.oid")) {
    while(r.next())definitions.append(r.getString(1)).append("\n");
   }
   Files.writeString(backup.resolve("functions-before.sql"),definitions);
   Files.writeString(backup.resolve("data-fingerprint-before.txt"),before);
   StringBuilder manifest=new StringBuilder();
   for(String file:List.of("V6__course_classes_and_enrollments.sql","V7__course_booking_and_payment_rules.sql","V8__course_integrity_and_runtime.sql")) {
    byte[] bytes=Files.readAllBytes(ROOT.resolve("database/migrations/"+file));
    manifest.append(file).append(" ").append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).append('\n');
    try(var s=db.createStatement()){s.execute(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));}
   }
   // Runtime grants are explicit; provision only after the three migrations succeed.
   try(var s=db.createStatement()){s.execute(Files.readString(ROOT.resolve("database/scripts/provision_runtime.sql")));}
   if(!before.equals(fingerprint(db)))throw new IllegalStateException("Existing application records changed; rolling back");
   try(var s=db.createStatement();var r=s.executeQuery("SELECT (SELECT count(*) FROM information_schema.tables WHERE table_schema='yoga' AND table_name IN ('courses','course_classes','course_enrollments'))=3 AND to_regprocedure('yoga.cancel_pending_course_order(uuid)') IS NOT NULL AND has_table_privilege('yoga_app','yoga.course_enrollments','INSERT') AND has_function_privilege('yoga_app','yoga.cancel_pending_course_order(uuid)','EXECUTE')")) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Upgrade verification failed");}
   Files.writeString(backup.resolve("migration-manifest.txt"),manifest);
   db.commit();
   System.out.println("COURSE_UPGRADE_COMMITTED: V6-V8 and runtime grants applied; all existing rows unchanged");
   System.out.println("RECOVERY_FILES="+backup);
  } catch(Exception e){db.rollback();throw e;}
 }
 public static void main(String[] args) throws Exception {
  if(args.length!=1||!Set.of("--check","--apply").contains(args[0]))throw new IllegalArgumentException("Use --check or --apply");
  try(var db=connect()){if(args[0].equals("--check")){db.setReadOnly(true);inspect(db);}else apply(db);}
 }
}
