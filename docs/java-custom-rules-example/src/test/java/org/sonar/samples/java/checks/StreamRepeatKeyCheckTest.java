package org.sonar.samples.java.checks;

import org.junit.Test;
import org.sonar.java.checks.verifier.CheckVerifier;

public class StreamRepeatKeyCheckTest {
  @Test
  public void test() {
    CheckVerifier.newVerifier()
      .onFile("src/test/files/StreamRepeatKeyRule.java")
      .withCheck(new StreamRepeatKeyRule())
      .verifyIssues();
  }
}
