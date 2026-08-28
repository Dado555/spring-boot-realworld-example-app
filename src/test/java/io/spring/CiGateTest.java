package io.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CiGateTest {

  @Test
  void deliberately_fails_to_prove_the_ci_gate_order() {
    assertThat(1).isEqualTo(2);
  }
}
