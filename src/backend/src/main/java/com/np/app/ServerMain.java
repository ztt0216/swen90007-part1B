package com.np.app;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;

public class ServerMain {
  public static void main(String[] args) throws Exception {
    Db.init(); // 若无 DATABASE_URL → 打印 memory mode；若有 → 连接并建表

    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    Server server = new Server(port);

    // 注册 Servlet 到 /api/availability
    ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
    ctx.setContextPath("/");
    ctx.addServlet(AvailabilityServlet.class, "/api/availability");

    server.setHandler(ctx);
    server.start();
    System.out.println("Jetty started on :" + port);
    server.join();
  }
}
