import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Read-only inspection; credentials stay in the private local configuration. */
class CheckMembershipExchange {
 public static void main(String[] args) throws Exception {
  var settings = new Properties();
  try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) { settings.load(reader); }
  var credentials=new Properties();
  credentials.setProperty("user",settings.getProperty("YOGA_DB_USERNAME"));
  credentials.setProperty("password",settings.getProperty("YOGA_DB_PASSWORD"));
  credentials.setProperty("connectTimeout","10"); credentials.setProperty("socketTimeout","20");
  try(var db=DriverManager.getConnection(settings.getProperty("YOGA_DB_JDBC_URL"),credentials)) {
   db.setReadOnly(true);
   String[] queries={
    "SELECT column_name FROM information_schema.columns WHERE table_schema='yoga' AND table_name='memberships' AND column_name LIKE 'exchange%' OR (table_schema='yoga' AND table_name='memberships' AND column_name='replaces_membership_id') ORDER BY column_name",
    "SELECT to_regprocedure('yoga.membership_exchange_quote(uuid,uuid,uuid,uuid)') IS NOT NULL AS quote_function_exists",
    "SELECT count(*) AS duplicate_current_students FROM (SELECT student_id FROM yoga.memberships WHERE status IN ('ACTIVE','FROZEN','EXPIRED') GROUP BY student_id HAVING count(*)>1) s",
    "SELECT count(*) AS duplicate_pending_students FROM (SELECT student_id FROM yoga.memberships WHERE status='PENDING_PAYMENT' GROUP BY student_id HAVING count(*)>1) s",
    "SELECT status,count(*) FROM yoga.memberships GROUP BY status ORDER BY status",
    "SELECT m.student_id,m.membership_code,p.name,m.remaining_sessions,m.total_sessions,m.start_date,m.end_date,m.purchased_price,m.exchange_credit,m.exchange_base_credit,(SELECT count(*) FROM yoga.bookings b WHERE b.membership_id=m.id AND b.status='CONFIRMED') AS reserved FROM yoga.memberships m JOIN yoga.membership_plans p ON p.id=m.plan_id ORDER BY m.created_at",
    "SELECT column_name,is_nullable,column_default FROM information_schema.columns WHERE table_schema='yoga' AND table_name='memberships' AND (column_name LIKE 'exchange%' OR column_name='replaces_membership_id') ORDER BY column_name",
    "SELECT conname FROM pg_constraint WHERE conrelid='yoga.memberships'::regclass ORDER BY conname",
    "SELECT tgname FROM pg_trigger WHERE tgrelid='yoga.memberships'::regclass AND NOT tgisinternal ORDER BY tgname"
   };
   for(String query:queries) try(var st=db.createStatement();var rows=st.executeQuery(query)) {
    var md=rows.getMetaData();
    for(int i=1;i<=md.getColumnCount();i++) System.out.print(md.getColumnLabel(i)+(i==md.getColumnCount()?"\n":" | "));
    while(rows.next()) { for(int i=1;i<=md.getColumnCount();i++) System.out.print(rows.getString(i)+(i==md.getColumnCount()?"\n":" | ")); }
   }
  }
 }
}
