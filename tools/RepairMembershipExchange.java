import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Transactional repair of the application's partially installed exchange rules. */
class RepairMembershipExchange {
 static String snapshot(Connection db) throws SQLException {
  try(var st=db.createStatement();var rows=st.executeQuery("SELECT COALESCE(jsonb_agg(jsonb_build_array(id,student_id,membership_code,status,remaining_sessions,total_sessions,start_date,end_date,purchased_price) ORDER BY id),'[]'::jsonb)::TEXT FROM yoga.memberships")) {
   rows.next(); return rows.getString(1);
  }
 }
 public static void main(String[] args) throws Exception {
  if(args.length!=1 || !"--apply".equals(args[0])) throw new IllegalArgumentException("Use --apply after reviewing repair_membership_exchange.sql");
  var settings=new Properties();
  try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) { settings.load(reader); }
  String url=settings.getProperty("YOGA_DB_JDBC_URL");
  String username=settings.getProperty("YOGA_DB_USERNAME");
  if(!url.contains("aws-0-ap-southeast-1.pooler.supabase.com") || !"postgres.cagwkxiccmayrtajwdtc".equals(username))
   throw new IllegalStateException("Unexpected database; review target before repair");
  var credentials=new Properties(); credentials.setProperty("user",username);
  credentials.setProperty("password",settings.getProperty("YOGA_DB_PASSWORD"));
  credentials.setProperty("connectTimeout","10"); credentials.setProperty("socketTimeout","60");
  String sql=Files.readString(Path.of("../database/scripts/repair_membership_exchange.sql"));
  try(var db=DriverManager.getConnection(url,credentials)) {
   db.setAutoCommit(false);
   try {
    try(var st=db.createStatement()) {
     st.execute("SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='45s'; LOCK TABLE yoga.memberships,yoga.orders,yoga.bookings,yoga.payments IN SHARE ROW EXCLUSIVE MODE");
    }
    String before=snapshot(db);
    try(var st=db.createStatement()) { st.execute(sql); }
    if(!before.equals(snapshot(db))) throw new IllegalStateException("Membership entitlements changed; rolling back");
    try(var st=db.createStatement();var rows=st.executeQuery("SELECT to_regprocedure('yoga.membership_exchange_quote(uuid,uuid,uuid,uuid)') IS NOT NULL AND EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='yoga.memberships'::regclass AND tgname='guard_membership_exchange')")) {
     if(!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException("Repair verification failed");
    }
    db.commit();
    System.out.println("REPAIR_COMMITTED: quote and transaction rules installed; membership entitlements unchanged");
   } catch(Exception e) { db.rollback(); throw e; }
  }
 }
}
