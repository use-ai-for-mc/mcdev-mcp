package dev.mcdevmcp.conformance;

import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Serial;
import java.util.Objects;

public final class ConformanceServlet extends HttpServlet {
    @Serial
    private static final long serialVersionUID = 1L;

    private final HttpServletStreamableServerTransportProvider delegate;

    public ConformanceServlet(HttpServletStreamableServerTransportProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        delegate.service(request, response);
    }
}
