import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Apply V16-V20 (online courses, notifications, instructor online rates, hybrid classes, live recordings) to the
 * configured application database in ONE transaction. Requires V15. Never prints credentials or personal data.
 * Run from backend/: java -cp postgresql.jar tools/UpdateOnlineExtras.java --check|--apply
 */
class UpdateOnlineExtras {
 static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 static final List<String> MIGRATIONS=List.of("V16__online_courses.sql","V17__notifications.sql","V18__instructor_online_rates.sql",
   "V19__hybrid_classes.sql","V20__live_recordings.sql");
 /** Existing rows must stay identical apart from the columns these migrations add (and fill for existing rows). */
 static final Map<String,List<String>> TABLES=new LinkedHashMap<>();
 static {
  for(String t:List.of("users","branches","rooms","membership_plans","memberships","course_enrollments","attendance_records",
     "orders","order_items","payments","integration_events","online_meetings")) TABLES.put(t,List.of());
  TABLES.put("bookings",List.of("attendance_mode"));
  TABLES.put("waitlists",List.of("attendance_mode"));
  TABLES.put("class_schedules",List.of("live_room_id","online_capacity","online_booked_count","record_session"));
  TABLES.put("course_classes",List.of("delivery_mode"));
  TABLES.put("video_lessons",List.of("source_schedule_id","course_class_id"));
  TABLES.put("instructor_rate_policies",List.of("online_pay_per_class","created_by"));
 }
 /** Functions V16-V20 replace: their old definitions are saved for recovery. */
 static final List<String> REPLACED=List.of("queue_online_registrant()","derive_delivery_mode()","maintain_booking_balances()",
   "guard_membership_scope()","validate_online_checkin()","queue_online_meeting()","guard_live_host_overlap()",
   "validate_booking()","validate_checkin()","validate_waitlist()","has_video_access(uuid,uuid)");

 static Connection connect() throws Exception {
  var settings=new Properties();
  try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) { settings.load(reader); }
  String url=settings.getProperty("YOGA_DB_JDBC_URL"), user=settings.getProperty("YOGA_DB_USERNAME");
  if(url==null||!url.contains("pooler.supabase.com")||!"postgres.cagwkxiccmayrtajwdtc".equals(user))
   throw new IllegalStateException("Configured database does not match the application target");
  var credentials=new Properties();credentials.setProperty("user",user);credentials.setProperty("password",settings.getProperty("YOGA_DB_PASSWORD"));
  credentials.setProperty("connectTimeout","15");credentials.setProperty("socketTimeout","300");
  return DriverManager.getConnection(url,credentials);
 }

 static void print(Connection db,String q) throws SQLException {
  try(var st=db.createStatement();var r=st.executeQuery(q)) {
   var md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++)System.out.print(md.getColumnLabel(i)+(i==md.getColumnCount()?"\n":" | "));
   while(r.next()){for(int i=1;i<=md.getColumnCount();i++)System.out.print(r.getString(i)+(i==md.getColumnCount()?"\n":" | "));}
  }
 }

 static final String READY="""
   SELECT to_regclass('yoga.video_lessons') IS NOT NULL
     AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='yoga' AND table_name='course_classes' AND column_name='delivery_mode')
     AND to_regclass('yoga.notifications') IS NULL AND to_regclass('yoga.live_recordings') IS NULL
     AND NOT EXISTS(SELECT 1 FROM pg_tables WHERE tablename='flyway_schema_history')""";

 static void inspect(Connection db) throws Exception {
  print(db,"SELECT version() AS database_version");
  print(db,"SELECT ("+READY+") AS ready_for_v16_v20");
  print(db,"SELECT count(*) AS course_classes,count(*) FILTER (WHERE EXISTS(SELECT 1 FROM yoga.class_schedules s JOIN yoga.rooms r ON r.id=s.room_id WHERE s.course_class_id=c.id AND r.room_type='VIRTUAL')) AS with_online_sessions FROM yoga.course_classes c");
  print(db,"SELECT s.delivery_mode,count(*) AS schedules,(SELECT count(*) FROM yoga.bookings b WHERE b.schedule_id IN (SELECT id FROM yoga.class_schedules x WHERE x.delivery_mode=s.delivery_mode)) AS bookings FROM yoga.class_schedules s GROUP BY s.delivery_mode ORDER BY 1");
  print(db,"SELECT count(*) AS rate_policies FROM yoga.instructor_rate_policies");
 }

 static String fingerprint(Connection db,boolean after) throws SQLException {
  StringBuilder all=new StringBuilder();
  for(var e:TABLES.entrySet()) {
   String minus=after&&!e.getValue().isEmpty()?"-ARRAY['"+String.join("','",e.getValue())+"']":"";
   String q="SELECT count(*)::text||':'||md5(COALESCE(jsonb_agg(to_jsonb(t)"+minus+" ORDER BY id)::text,'[]')) FROM yoga."+e.getKey()+" t";
   try(var s=db.createStatement();var r=s.executeQuery(q)){r.next();all.append(e.getKey()).append('|').append(r.getString(1)).append('\n');}
  }
  return all.toString();
 }

 static void apply(Connection db) throws Exception {
  db.setAutoCommit(false);
  try {
   try(var st=db.createStatement()) {
    st.execute("SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='240s'; SET LOCAL search_path=yoga,public; SELECT pg_advisory_xact_lock(hashtextextended('yoga-online-extras-v16-v20',0)); LOCK TABLE yoga.class_schedules,yoga.bookings,yoga.waitlists,yoga.course_classes,yoga.course_enrollments,yoga.attendance_records IN SHARE ROW EXCLUSIVE MODE");
    try(var r=st.executeQuery(READY)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Database is not at V15 or V16+ is already applied; no changes committed");}
   }
   String before=fingerprint(db,false);
   Path backup=ROOT.resolve("database/backups/online-extras-v16-v20-"+System.currentTimeMillis());Files.createDirectories(backup);
   StringBuilder definitions=new StringBuilder("-- Functions before V16-V20, for recovery; contains no application row data.\n");
   for(String fn:REPLACED) {
    try(var s=db.createStatement();var r=s.executeQuery("SELECT pg_get_functiondef('yoga."+fn+"'::regprocedure)")) {
     while(r.next())definitions.append(r.getString(1)).append(";\n\n");
    }
   }
   Files.writeString(backup.resolve("before.sql"),definitions);
   Files.writeString(backup.resolve("fingerprint-before.txt"),before);
   StringBuilder sums=new StringBuilder();
   for(String m:MIGRATIONS) {
    byte[] bytes=Files.readAllBytes(ROOT.resolve("database/migrations/"+m));
    sums.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).append(" *database/migrations/").append(m).append('\n');
    try(var s=db.createStatement()){s.execute(new String(bytes,StandardCharsets.UTF_8));}
   }
   Files.writeString(backup.resolve("migrations.sha256"),sums);
   // V16 deliberately drops Zoom registrants queued for never-paid course enrollments; report how many.
   try(var s=db.createStatement();var r=s.executeQuery("""
     SELECT count(*) FROM yoga.online_meeting_registrants r JOIN yoga.bookings b ON b.id=r.booking_id
     JOIN yoga.course_enrollments e ON e.id=b.course_enrollment_id WHERE e.status='PENDING_PAYMENT' AND r.status='REMOVED'""")) {
    r.next();System.out.println("UNPAID_COURSE_REGISTRANTS_DROPPED="+r.getLong(1));
   }
   String after=fingerprint(db,true);
   if(!before.equals(after)) {
    Files.writeString(backup.resolve("fingerprint-after-mismatch.txt"),after);
    throw new IllegalStateException("Existing application records changed; rolling back");
   }
   String verify="""
     SELECT to_regclass('yoga.notifications') IS NOT NULL AND to_regclass('yoga.live_recordings') IS NOT NULL
       AND NOT EXISTS(SELECT 1 FROM yoga.bookings WHERE attendance_mode IS NULL)
       AND NOT EXISTS(SELECT 1 FROM yoga.bookings b JOIN yoga.class_schedules s ON s.id=b.schedule_id
                      WHERE b.attendance_mode<>CASE WHEN s.delivery_mode='ONLINE' THEN 'ONLINE' ELSE 'IN_PERSON' END)
       AND (SELECT count(*) FROM pg_trigger WHERE tgname='derive_attendance_mode')=2
       AND to_regprocedure('yoga.generate_notifications()') IS NOT NULL
       AND to_regprocedure('yoga.payroll_lines(timestamptz,timestamptz,uuid,uuid)') IS NOT NULL
       AND (SELECT bool_and(p.prosecdef) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='yoga'
            AND p.proname IN ('validate_booking','validate_checkin','validate_waitlist','derive_delivery_mode','derive_attendance_mode',
              'maintain_booking_balances','guard_membership_scope','queue_online_registrant','queue_course_registrants',
              'generate_notifications','payroll_lines','has_video_access','guard_live_recording'))
       AND has_table_privilege('yoga_app','yoga.notifications','INSERT')
       AND has_table_privilege('yoga_app','yoga.live_recordings','INSERT')""";
   try(var s=db.createStatement();var r=s.executeQuery(verify)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Upgrade verification failed; rolling back");}
   Files.writeString(backup.resolve("fingerprint-after.txt"),after);
   db.commit();
   System.out.println("V16_V20_COMMITTED: online courses, notifications, online rates, hybrid classes, live recordings; existing rows unchanged");
   System.out.println("RECOVERY_FILES="+backup);
  } catch(Exception e){db.rollback();throw e;}
 }

 public static void main(String[] args) throws Exception {
  if(args.length!=1||!Set.of("--check","--apply").contains(args[0]))throw new IllegalArgumentException("Use --check or --apply");
  try(var db=connect()){if(args[0].equals("--check")){db.setReadOnly(true);inspect(db);}else apply(db);}
 }
}
