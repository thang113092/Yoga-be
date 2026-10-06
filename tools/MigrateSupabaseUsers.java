import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/** Explicit, repeatable migration through the supported Supabase Admin API. */
class MigrateSupabaseUsers {
 public static void main(String[] args) throws Exception {
  boolean apply=args.length==1 && args[0].equals("--apply");
  var settings=new Properties();try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))){settings.load(reader);}
  String url=settings.getProperty("YOGA_DB_JDBC_URL");String username=settings.getProperty("YOGA_DB_USERNAME");
  if(!url.contains("aws-0-ap-southeast-1.pooler.supabase.com") || !"postgres.cagwkxiccmayrtajwdtc".equals(username)) throw new IllegalStateException("Unexpected project/database; review tool target first.");
  String key=settings.getProperty("SUPABASE_SERVICE_ROLE_KEY",System.getenv().getOrDefault("SUPABASE_SERVICE_ROLE_KEY",""));
  if(apply && key.isBlank())throw new IllegalStateException("Fill SUPABASE_SERVICE_ROLE_KEY in the private local config first.");
  var credentials=new Properties();credentials.setProperty("user",username);credentials.setProperty("password",settings.getProperty("YOGA_DB_PASSWORD"));credentials.setProperty("connectTimeout","10");credentials.setProperty("socketTimeout","20");
  var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();var json=new ObjectMapper();
  int created=0,linked=0,blocked=0;
  try(var db=DriverManager.getConnection(url,credentials)){
   record Candidate(UUID id,String email,String phone,String name,String hash,String role){}
   var candidates=new ArrayList<Candidate>();
   try(var statement=db.createStatement();var rows=statement.executeQuery("SELECT u.id,u.email,u.phone,u.full_name,u.password_hash,r.code FROM yoga.users u JOIN yoga.roles r ON r.id=u.role_id WHERE u.supabase_user_id IS NULL AND u.is_active=true ORDER BY u.created_at")){
    while(rows.next())candidates.add(new Candidate(rows.getObject(1,UUID.class),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6)));
   }
   for(var user:candidates){
    if(user.email()==null || user.email().isBlank()){blocked++;System.out.println(user.id()+" "+user.role()+" NEEDS_REAL_EMAIL");continue;}
    UUID authId=null;boolean confirmed=false;
    try(var lookup=db.prepareStatement("SELECT id,email_confirmed_at IS NOT NULL FROM auth.users WHERE lower(email)=lower(?) AND deleted_at IS NULL")){
     lookup.setString(1,user.email());try(var rows=lookup.executeQuery()){if(rows.next()){authId=rows.getObject(1,UUID.class);confirmed=rows.getBoolean(2);if(rows.next())throw new IllegalStateException("Multiple identities for one legacy email; manual review required.");}}
    }
    if(authId!=null && !confirmed){blocked++;System.out.println(user.id()+" "+user.role()+" EXISTING_AUTH_EMAIL_UNCONFIRMED");continue;}
    if(authId==null && (user.hash()==null || !user.hash().matches("^\\$2[aby]\\$.*"))){blocked++;System.out.println(user.id()+" "+user.role()+" NEEDS_PASSWORD_RESET_OR_MANUAL_LINK");continue;}
    if(!apply){System.out.println(user.id()+" "+user.role()+" "+(authId==null?"CREATE_AUTH_PRESERVE_PASSWORD_REQUIRE_EMAIL_CONFIRMATION":"LINK_VERIFIED_AUTH_PRESERVE_BUSINESS_ID"));continue;}
    if(authId==null){
     var metadata=new LinkedHashMap<String,Object>();metadata.put("full_name",user.name());metadata.put("phone",user.phone());
     var body=json.writeValueAsString(Map.of("id",user.id().toString(),"email",user.email().trim().toLowerCase(Locale.ROOT),"password_hash",user.hash(),"email_confirm",false,"user_metadata",metadata));
     var request=HttpRequest.newBuilder(URI.create("https://cagwkxiccmayrtajwdtc.supabase.co/auth/v1/admin/users")).timeout(Duration.ofSeconds(20)).header("apikey",key).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
     var response=http.send(request,HttpResponse.BodyHandlers.ofString());
     if(response.statusCode()<200 || response.statusCode()>=300)throw new IllegalStateException("Supabase create failed with HTTP "+response.statusCode()+" for business ID "+user.id()+"; reconcile before retry.");
     authId=UUID.fromString(json.readTree(response.body()).path("id").asText());created++;
    }
    try(var update=db.prepareStatement("UPDATE yoga.users SET supabase_user_id=?,password_hash='!SUPABASE_ONLY' WHERE id=? AND supabase_user_id IS NULL")){update.setObject(1,authId);update.setObject(2,user.id());if(update.executeUpdate()!=1)throw new IllegalStateException("Profile changed concurrently; reconcile business ID "+user.id());}
    linked++;System.out.println(user.id()+" "+user.role()+" LINKED");
   }
  }
  System.out.println("mode="+(apply?"apply":"preview")+", created="+created+", linked="+linked+", blocked="+blocked+"; no emails sent and no existing Auth passwords changed.");
 }
}
