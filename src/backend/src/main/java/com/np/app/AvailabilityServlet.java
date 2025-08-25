package com.np.app;

import jakarta.servlet.http.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.json.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

@WebServlet(urlPatterns={"/api/availability"})
public class AvailabilityServlet extends HttpServlet {

  // —— 内存存储（当没有 DATABASE_URL 时使用）——
  private static final List<Slot> STORE = Collections.synchronizedList(new ArrayList<>());
  private static final Pattern HHMM = Pattern.compile("\\d{2}:\\d{2}");
  private static final int DRIVER_ID = 1; // 先固定为 1，足够做 Part 1B 演示

  private static final class Slot {
    final int day; final String start; final String end;
    Slot(int d, String s, String e){ this.day=d; this.start=s; this.end=e; }
  }

  // —— 小工具 —— //
  private static String readBody(HttpServletRequest req) throws IOException {
    req.setCharacterEncoding("UTF-8");
    StringBuilder sb = new StringBuilder();
    try (BufferedReader br = req.getReader()) { String line; while((line=br.readLine())!=null) sb.append(line); }
    return sb.toString();
  }
  private static void writeJson(HttpServletResponse resp, int status, JsonStructure json) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json; charset=UTF-8");
    try (var w = resp.getWriter()) { Json.createWriterFactory(Map.of()).createWriter(w).write(json); }
  }
  private static boolean valid(int d, String s, String e){
    return d>=0 && d<=6 && s!=null && e!=null && HHMM.matcher(s).matches() && HHMM.matcher(e).matches() && s.compareTo(e)<0;
  }
  private static boolean overlap(String aS,String aE,String bS,String bE){
    return !(aE.compareTo(bS) <= 0 || aS.compareTo(bE) >= 0);
  }

  // —— DB 相关 —— //
  private static boolean useDb() { return Db.available(); }

  private static List<Slot> dbList() throws Exception {
    List<Slot> out = new ArrayList<>();
    try (Connection c = Db.get();
         PreparedStatement ps = c.prepareStatement(
           "SELECT day_of_week, to_char(start_time,'HH24:MI') s, to_char(end_time,'HH24:MI') e " +
           "FROM availability WHERE driver_id=? ORDER BY day_of_week, start_time")) {
      ps.setInt(1, DRIVER_ID);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(new Slot(rs.getInt(1), rs.getString(2), rs.getString(3)));
      }
    }
    return out;
  }

  private static void dbInsertSlots(List<Slot> list) throws Exception {
    try (Connection c = Db.get()) {
      c.setAutoCommit(false);
      try (PreparedStatement overlap = c.prepareStatement(
             "SELECT 1 FROM availability WHERE driver_id=? AND day_of_week=? " +
             "AND NOT (end_time <= ?::time OR start_time >= ?::time) LIMIT 1");
           PreparedStatement insert = c.prepareStatement(
             "INSERT INTO availability(driver_id, day_of_week, start_time, end_time) " +
             "VALUES (?,?,?::time,?::time) ON CONFLICT DO NOTHING")) {

        // 重叠检测
        for (Slot sl : list) {
          overlap.setInt(1, DRIVER_ID);
          overlap.setInt(2, sl.day);
          overlap.setString(3, sl.start);
          overlap.setString(4, sl.end);
          try (ResultSet rs = overlap.executeQuery()) {
            if (rs.next()) { c.rollback(); throw new IllegalStateException("Overlaps existing slot (day "+sl.day+")"); }
          }
        }
        // 插入
        for (Slot sl : list) {
          insert.setInt(1, DRIVER_ID);
          insert.setInt(2, sl.day);
          insert.setString(3, sl.start);
          insert.setString(4, sl.end);
          insert.executeUpdate();
        }
        c.commit();
      } catch (Exception ex) {
        c.rollback(); throw ex;
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  private static boolean dbDeleteOne(Slot s) throws Exception {
    try (Connection c = Db.get();
         PreparedStatement ps = c.prepareStatement(
           "DELETE FROM availability WHERE driver_id=? AND day_of_week=? AND start_time=?::time AND end_time=?::time")) {
      ps.setInt(1, DRIVER_ID);
      ps.setInt(2, s.day);
      ps.setString(3, s.start);
      ps.setString(4, s.end);
      return ps.executeUpdate() > 0;
    }
  }

  // —— 路由实现 —— //
  @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (useDb()) {
      try {
        var arr = Json.createArrayBuilder();
        for (Slot s : dbList()) {
          arr.add(Json.createObjectBuilder().add("dayOfWeek", s.day).add("startTime", s.start).add("endTime", s.end));
        }
        writeJson(resp, 200, arr.build());
      } catch (Exception ex) {
        writeJson(resp, 500, Json.createObjectBuilder().add("ok", false).add("error", "DB error").build());
      }
      return;
    }
    // 内存模式
    var arr = Json.createArrayBuilder();
    synchronized (STORE) {
      for (Slot s : STORE) {
        arr.add(Json.createObjectBuilder().add("dayOfWeek", s.day).add("startTime", s.start).add("endTime", s.end));
      }
    }
    writeJson(resp, 200, arr.build());
  }

  @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String body = readBody(req);
      if (body==null || body.isBlank()){
        writeJson(resp,400,Json.createObjectBuilder().add("ok",false).add("error","Empty body").build()); return;
      }
      var val = Json.createReader(new StringReader(body)).read();
      JsonArray slots;
      if (val.getValueType()==JsonValue.ValueType.ARRAY) slots = (JsonArray) val;
      else if (val.getValueType()==JsonValue.ValueType.OBJECT) {
        JsonObject obj = (JsonObject) val;
        if (!obj.containsKey("slots") || obj.get("slots").getValueType()!=JsonValue.ValueType.ARRAY) {
          writeJson(resp,422,Json.createObjectBuilder().add("ok",false).add("error","Missing 'slots' array").build()); return;
        }
        slots = obj.getJsonArray("slots");
      } else { writeJson(resp,422,Json.createObjectBuilder().add("ok",false).add("error","Invalid JSON").build()); return; }

      List<Slot> incoming = new ArrayList<>();
      for (JsonValue v : slots) {
        if (v.getValueType()!=JsonValue.ValueType.OBJECT) { writeJson(resp,422,Json.createObjectBuilder().add("ok",false).add("error","Slot must be object").build()); return; }
        JsonObject o = (JsonObject) v;
        int d = o.getInt("dayOfWeek", -1);
        String s = o.getString("startTime", null);
        String e = o.getString("endTime", null);
        if (!valid(d,s,e)) { writeJson(resp,422,Json.createObjectBuilder().add("ok",false).add("error","Invalid slot fields").build()); return; }
        incoming.add(new Slot(d,s,e));
      }

      if (useDb()) {
        try {
          dbInsertSlots(incoming);
          writeJson(resp,200,Json.createObjectBuilder().add("ok",true).build());
        } catch (IllegalStateException overlap) {
          writeJson(resp,422,Json.createObjectBuilder().add("ok",false).add("error", overlap.getMessage()).build());
        } catch (Exception ex) {
          writeJson(resp,500,Json.createObjectBuilder().add("ok",false).add("error","DB error").build());
        }
        return;
      }

      // 内存模式
      synchronized (STORE) {
        for (Slot add : incoming) {
          for (Slot ex : STORE) {
            if (add.day==ex.day && overlap(add.start,add.end, ex.start,ex.end)) {
              writeJson(resp,422,Json.createObjectBuilder().add("ok",false).add("error","Overlaps existing slot").build()); return;
            }
          }
        }
        STORE.addAll(incoming);
      }
      writeJson(resp,200,Json.createObjectBuilder().add("ok",true).build());

    } catch (Exception ex) {
      writeJson(resp,500,Json.createObjectBuilder().add("ok",false).add("error","Server error").build());
    }
  }

  @Override protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String d = req.getParameter("dayOfWeek");
    String s = req.getParameter("startTime");
    String e = req.getParameter("endTime");
    int day;
    try { day = Integer.parseInt(d); }
    catch (Exception ignore) { writeJson(resp,422,Json.createObjectBuilder().add("ok",false).add("error","dayOfWeek must be 0..6").build()); return; }
    if (!valid(day,s,e)) { writeJson(resp,422,Json.createObjectBuilder().add("ok",false).add("error","Invalid parameters").build()); return; }

    if (useDb()) {
      try {
        boolean ok = dbDeleteOne(new Slot(day,s,e));
        if (ok) writeJson(resp,200,Json.createObjectBuilder().add("ok",true).build());
        else    writeJson(resp,404,Json.createObjectBuilder().add("ok",false).add("error","Not found").build());
      } catch (Exception ex) {
        writeJson(resp,500,Json.createObjectBuilder().add("ok",false).add("error","DB error").build());
      }
      return;
    }

    // 内存模式
    boolean removed = false;
    synchronized (STORE) {
      Iterator<Slot> it = STORE.iterator();
      while (it.hasNext()) {
        Slot x = it.next();
        if (x.day==day && x.start.equals(s) && x.end.equals(e)) { it.remove(); removed = true; break; }
      }
    }
    if (removed) writeJson(resp,200,Json.createObjectBuilder().add("ok",true).build());
    else         writeJson(resp,404,Json.createObjectBuilder().add("ok",false).add("error","Not found").build());
  }

  @Override protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setStatus(204);
  }
}
