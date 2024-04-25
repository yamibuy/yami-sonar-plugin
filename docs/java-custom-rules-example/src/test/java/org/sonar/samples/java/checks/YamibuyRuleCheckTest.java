package org.sonar.samples.java.checks;

import org.junit.Test;
import org.sonar.java.checks.verifier.CheckVerifier;

public class YamibuyRuleCheckTest {
  @Test
  public void testStreamRepeatKeyRule() {
    CheckVerifier.newVerifier()
      .onFile("src/test/files/StreamRepeatKeyRule.java")
      .withCheck(new StreamRepeatKeyRule())
      .verifyIssues();
  }

  @Test
  public void testForStatementTreeIoRule() {
    CheckVerifier.newVerifier()
      .onFile("src/test/files/ForStatementTreeIoRule.java")
      .withCheck(new ForStatementTreeIoRule())
      .verifyIssues();
  }
}
