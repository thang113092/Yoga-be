import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Read-only verification of the exact quote parameters reported in the UI. */
class VerifyMembershipExchange {
 public static void main(String[] args) throws Exception {
  var settings=new Properties();
  try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) { settings.load(reader); }
  var credentials=new Properties(); credentials.setProperty("user",settings.getProperty("YOGA_DB_USERNAME"));
  credentials.setProperty("password",settings.getProperty("YOGA_DB_PASSWORD"));
  credentials.setProperty("connectTimeout","10"); credentials.setProperty("socketTimeout","20");
  try(var db=DriverManager.getConnection(settings.getProperty("YOGA_DB_JDBC_URL"),credentials)) {
   db.setReadOnly(true);
   try(var st=db.prepareStatement("SELECT current_id,credit,issue,contract_value FROM yoga.membership_exchange_quote(?,?,?,NULL)")) {
    st.setObject(1,UUID.fromString("615c892b-2963-4d51-be66-c26fc7d49e82"));
    st.setObject(2,UUID.fromString("e0000000-0000-0000-0000-000000000001"));
    st.setObject(3,UUID.fromString("b0000000-0000-0000-0000-000000000002"));
    try(var rows=st.executeQuery()) {
     if(!rows.next()) throw new IllegalStateException("No quote returned");
     System.out.println("UI_QUOTE_OK: current_card="+rows.getString(1)+", credit="+rows.getBigDecimal(2)+", issue="+rows.getString(3));
     if(rows.next()) throw new IllegalStateException("Multiple quotes returned");
    }
   }
   try(var st=db.createStatement();var rows=st.executeQuery("SELECT has_function_privilege('yoga_app','yoga.membership_exchange_quote(uuid,uuid,uuid,uuid)','EXECUTE'), EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='yoga.memberships'::regclass AND tgname='guard_membership_exchange'), EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname='yoga' AND indexname='one_pending_membership')")) {
    rows.next();
    if(!rows.getBoolean(1)||!rows.getBoolean(2)||!rows.getBoolean(3)) throw new IllegalStateException("Runtime permission or integrity guard missing");
    System.out.println("RUNTIME_PERMISSION_AND_GUARDS_OK");
   }
   try(var st=db.createStatement();var rows=st.executeQuery("SELECT count(*) FROM (SELECT student_id FROM yoga.memberships WHERE status IN ('ACTIVE','FROZEN','EXPIRED') GROUP BY student_id HAVING count(*)>1) s")) {
    rows.next(); System.out.println("STUDENTS_REQUIRING_RECONCILIATION="+rows.getInt(1));
   }
  }
 }
}
