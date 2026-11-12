import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Apply V15 (video library tables, entitlement functions, Bunny integration events) to the configured application
 * database in one transaction. Requires V14. Never prints credentials or personal data.
 * Run from backend/: java -cp postgresql.jar tools/UpdateVideoLibrary.java --check|--apply
 */
class UpdateVideoLibrary {
 static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 static final String MIGRATION="V15__video_library.sql";
 /** Existing rows must stay identical; V15 only adds tables (and seeds video topics). */
 static final List<String> TABLES=List.of("users","branches","rooms","membership_plans","memberships","bookings","waitlists",
   "class_schedules","attendance_records","orders","order_items","payments","user_branches","integration_events");

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
   SELECT to_regprocedure('yoga.guard_membership_scope()') IS NOT NULL
     AND to_regclass('yoga.video_lessons') IS NULL
     AND EXISTS(SELECT 1 FROM pg_constraint WHERE conname='integration_events_provider_check')
     AND NOT EXISTS(SELECT 1 FROM pg_tables WHERE tablename='flyway_schema_history')""";

 static void inspect(Connection db) throws Exception {
  print(db,"SELECT version() AS database_version");
  print(db,"SELECT ("+READY+") AS ready_for_v15");
  print(db,"SELECT access_scope,count(*) AS plans FROM yoga.membership_plans GROUP BY access_scope ORDER BY access_scope");
  print(db,"SELECT provider,count(*) AS integration_events FROM yoga.integration_events GROUP BY provider ORDER BY provider");
 }

 static String fingerprint(Connection db) throws SQLException {
  StringBuilder all=new StringBuilder();
  for(String table:TABLES) {
   String q="SELECT count(*)::text||':'||md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]')) FROM yoga."+table+" t";
   try(var s=db.createStatement();var r=s.executeQuery(q)){r.next();all.append(table).append('|').append(r.getString(1)).append('\n');}
  }
  return all.toString();
 }

 static void apply(Connection db) throws Exception {
  db.setAutoCommit(false);
  try {
   try(var st=db.createStatement()) {
    st.execute("SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='120s'; SET LOCAL search_path=yoga,public; SELECT pg_advisory_xact_lock(hashtextextended('yoga-video-library-v15',0)); LOCK TABLE yoga.integration_events IN SHARE ROW EXCLUSIVE MODE");
    try(var r=st.executeQuery(READY)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Database is not at V14 or V15 is already applied; no changes committed");}
   }
   String before=fingerprint(db);
   Path backup=ROOT.resolve("database/backups/video-library-v15-"+System.currentTimeMillis());Files.createDirectories(backup);
   StringBuilder definitions=new StringBuilder("-- V15 only adds objects. To undo: drop the video_* tables, video_pass, has_video_access and the\n"
     +"-- guard_video_* functions, then restore this constraint on integration_events:\n");
   try(var s=db.createStatement();var r=s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='integration_events_provider_check'")) {
    while(r.next())definitions.append("-- integration_events_provider_check ").append(r.getString(1)).append("\n");
   }
   Files.writeString(backup.resolve("before.sql"),definitions);
   Files.writeString(backup.resolve("fingerprint-before.txt"),before);
   byte[] bytes=Files.readAllBytes(ROOT.resolve("database/migrations/"+MIGRATION));
   Files.writeString(backup.resolve("migrations.sha256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))+" *database/migrations/"+MIGRATION+"\n");
   try(var s=db.createStatement()){s.execute(new String(bytes,StandardCharsets.UTF_8));}
   String after=fingerprint(db);
   if(!before.equals(after))throw new IllegalStateException("Existing application records changed; rolling back");
   String verify="""
     SELECT (SELECT count(*) FROM information_schema.tables WHERE table_schema='yoga' AND left(table_name,6)='video_')=8
       AND (SELECT count(*) FROM yoga.video_tags)>=7
       AND (SELECT count(*) FROM pg_trigger WHERE tgname IN ('guard_video_lesson','guard_video_collection_item','guard_video_playback','guard_video_progress'))=4
       AND (SELECT bool_and(p.prosecdef) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='yoga'
            AND p.proname IN ('video_pass','has_video_access','guard_video_lesson','guard_video_collection_item','guard_video_playback','guard_video_progress'))
       AND has_table_privilege('yoga_app','yoga.video_watch_progress','INSERT')""";
   try(var s=db.createStatement();var r=s.executeQuery(verify)) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Upgrade verification failed; rolling back");}
   Files.writeString(backup.resolve("fingerprint-after.txt"),after);
   db.commit();
   System.out.println("V15_COMMITTED: video library tables created; existing rows unchanged");
   System.out.println("RECOVERY_FILES="+backup);
  } catch(Exception e){db.rollback();throw e;}
 }

 public static void main(String[] args) throws Exception {
  if(args.length!=1||!Set.of("--check","--apply").contains(args[0]))throw new IllegalArgumentException("Use --check or --apply");
  try(var db=connect()){if(args[0].equals("--check")){db.setReadOnly(true);inspect(db);}else apply(db);}
 }
}
