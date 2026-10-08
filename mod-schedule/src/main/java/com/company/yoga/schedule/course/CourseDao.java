package com.company.yoga.schedule.course;

import java.sql.Timestamp;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC projections keep course reporting and financial snapshots in one transaction. */
@Repository
@RequiredArgsConstructor
public class CourseDao {
    private final NamedParameterJdbcTemplate jdbc;
    public List<Map<String,Object>> rows(String sql, Map<String,?> args) {
        return jdbc.query(sql,args,(rs,n)-> {
            Map<String,Object> row = new LinkedHashMap<>();
            for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
                String name=rs.getMetaData().getColumnLabel(i);
                StringBuilder key=new StringBuilder(); boolean upper=false;
                for(char c:name.toCharArray()) { if(c=='_') upper=true; else { key.append(upper?Character.toUpperCase(c):c); upper=false; } }
                Object value=rs.getObject(i);
                row.put(key.toString(),value instanceof Timestamp t?t.toInstant():value);
            }
            return row;
        });
    }
    public int update(String sql,Map<String,?> args) { return jdbc.update(sql,args); }
    public long count(String sql,Map<String,?> args) { return Objects.requireNonNull(jdbc.queryForObject(sql,args,Long.class)); }
}
