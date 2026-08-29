package io.spring.api.security;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

// step 9.2: reads X-Request-Id (or generates one), puts it in MDC so every log
// line for this request carries it, echoes it back on the response. only ever
// reads/writes this one header - never touches Authorization or logs headers
// wholesale (see the security note on WebSecurityConfig's jwtTokenFilter()).
public class CorrelationIdFilter extends OncePerRequestFilter {
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String MDC_KEY = "requestId";

  // client-supplied ids are trusted into every log line and echoed back on the
  // response - constrained charset/length so a hostile value (control chars,
  // multi-KB padding) can't inflate log volume or trigger header-value
  // rejection. covers uuids and any typical correlation-id format.
  private static final Pattern VALID_REQUEST_ID = Pattern.compile("[a-zA-Z0-9_-]{1,128}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = request.getHeader(REQUEST_ID_HEADER);
    if (requestId == null || !VALID_REQUEST_ID.matcher(requestId).matches()) {
      requestId = UUID.randomUUID().toString();
    }

    // response header must be set before anything downstream could commit the
    // response (write the body) - safe here since this runs first in the chain
    response.setHeader(REQUEST_ID_HEADER, requestId);
    MDC.put(MDC_KEY, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      // tomcat's worker threads are pooled - without this, a later unrelated
      // request handled by the same thread would inherit a stale requestId
      MDC.remove(MDC_KEY);
    }
  }

  // an uncaught exception is handled by a fresh container-level dispatch to
  // /error (CustomizeExceptionHandler has no catch-all @ExceptionHandler, so
  // this is the real path for anything unexpected). OncePerRequestFilter skips
  // that dispatch by default, which would leave exactly the error logs that
  // matter most without a requestId - the underlying request object (and its
  // X-Request-Id header) is unchanged, so re-running is safe and idempotent.
  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }
}
