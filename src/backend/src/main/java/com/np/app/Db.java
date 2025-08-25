package com.np.app;

import java.net.URI;
import java.sql.*;

public class Db {
  public static boolean available() {
    String url = System.getenv("DATABASE_URL");
    return url != null && !url.isBlank();
  }

  public static Connection get() throws Exception {
  String url = System.getenv("DATABASE_URL");
  if (url == null || url.isBlank()) return null;

  String jdbcUrl, user = null, pass = null;

  // ★ 同时接受 postgresql:// 或 postgres://
  if (url.startsWith("postgresql://") || url.startsWith("postgres://")) {
    String norm = url.replaceFirst("^postgresql://", "postgres://");
    URI u = URI.create(norm);
    String[] up = (u.getUserInfo()==null) ? new String[]{"",""} : u.getUserInfo().split(":",2);
    user = up.length>0? up[0] : "";
    pass = up.length>1? up[1] : "";
    String host = u.getHost();
    int port = (u.getPort()==-1) ? 5432 : u.getPort();   // 没写端口就用 5432
    String db   = u.getPath().replaceFirst("/", "");
    jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db + "?sslmode=require";
  } else if (url.startsWith("jdbc:postgresql://")) {
    jdbcUrl = url;
    user = System.getenv("DB_USER");
    pass = System.getenv("DB_PASSWORD");
  } else {
    throw new IllegalArgumentException("Unsupported DATABASE_URL: " + url);
  }

    Class.forName("org.postgresql.Driver");
    return DriverManager.getConnection(jdbcUrl, user, pass);
  }

  public static void init() {
    try (Connection c = get()) {
      if (c == null) {
        System.out.println("DB: DATABASE_URL not set → memory mode");
        return;
      }
      try (Statement st = c.createStatement()) {
        st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS availability (
            id SERIAL PRIMARY KEY,
            driver_id INT NOT NULL,
            day_of_week INT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
            start_time TIME NOT NULL,
            end_time   TIME NOT NULL,
            UNIQUE(driver_id, day_of_week, start_time, end_time)
          )
        """);
        st.executeUpdate("""
          CREATE INDEX IF NOT EXISTS idx_avail_driver_day
          ON availability(driver_id, day_of_week)
        """);
      }
      System.out.println("DB: connected and ensured schema");
    } catch (Exception e) {
      System.out.println("DB: init failed → " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
