import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Apply only the additive program-plan migration to the configured application database. */
class UpdateProgramPlans {
 public static void main(String[] args) throws Exception {
  var settings=new Properties();
  try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) {settings.load(reader);}
  String url=settings.getProperty("YOGA_DB_JDBC_URL"), user=settings.getProperty("YOGA_DB_USERNAME");
  if(url==null||!url.contains("aws-0-ap-southeast-1.pooler.supabase.com")||!"postgres.cagwkxiccmayrtajwdtc".equals(user)) throw new IllegalStateException("Configured application database does not match expected target");
  var credentials=new Properties();credentials.setProperty("user",user);credentials.setProperty("password",settings.getProperty("YOGA_DB_PASSWORD"));credentials.setProperty("connectTimeout","15");credentials.setProperty("socketTimeout","30");
  try(var db=DriverManager.getConnection(url,credentials)) {
   db.setAutoCommit(false);
   try {
    try(var st=db.createStatement();var rs=st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='yoga' AND table_name IN ('course_program_sessions','course_class_program_sessions')")) {
     rs.next();int existing=rs.getInt(1);
     if(existing==2){System.out.println("PROGRAM_PLAN_SCHEMA_ALREADY_PRESENT");db.rollback();return;}
     if(existing!=0)throw new IllegalStateException("Partial schema detected; migration aborted");
    }
    try(var st=db.createStatement()){st.execute("SET LOCAL lock_timeout = '5s'");st.execute(Files.readString(Path.of("../database/migrations/V9__course_program_session_plans.sql")));}
    try(var st=db.createStatement();var rs=st.executeQuery("SELECT has_table_privilege('yoga_app','yoga.course_program_sessions','SELECT,INSERT') AND has_table_privilege('yoga_app','yoga.course_class_program_sessions','SELECT,INSERT')")){rs.next();if(!rs.getBoolean(1))throw new IllegalStateException("Runtime grant verification failed");}
    db.commit();System.out.println("PROGRAM_PLAN_SCHEMA_APPLIED: additive tables and runtime grants; existing records unchanged");
   }catch(Exception error){db.rollback();throw error;}
  }
 }
}
