package io.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Step 1.5 / B13: application.properties now requires ${JWT_SECRET} from the environment
// with no default. This test boots the full context with no profile active by default, so
// without pinning it to the "test" profile it would try (and fail) to resolve that
// placeholder. "test" is activated here, matching the same pattern already used by
// ArticleRepositoryTransactionTest, so it picks up the test-only jwt.secret defined in
// application-test.properties instead.
@ActiveProfiles("test")
@SpringBootTest
public class RealworldApplicationTests {

  @Test
  public void contextLoads() {}
}
