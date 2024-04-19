/*
 * SonarQube Java
 * Copyright (C) 2012-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.samples.java;

import org.junit.jupiter.api.Test;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.debt.DebtRemediationFunction.Type;
import org.sonar.api.server.rule.RuleParamType;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.server.rule.RulesDefinition.Param;
import org.sonar.api.server.rule.RulesDefinition.Repository;
import org.sonar.api.server.rule.RulesDefinition.Rule;

import static org.assertj.core.api.Assertions.assertThat;

class MyJavaRulesDefinitionTest {

  @Test
  void test() {
    MyJavaRulesDefinition rulesDefinition = new MyJavaRulesDefinition();
    RulesDefinition.Context context = new RulesDefinition.Context();
    rulesDefinition.define(context);
    RulesDefinition.Repository repository = context.repository(MyJavaRulesDefinition.REPOSITORY_KEY);

    assertThat(repository.name()).isEqualTo("Yamibuy Custom Repository");
    assertThat(repository.language()).isEqualTo("java");
    assertThat(repository.rules()).hasSize(RulesList.getChecks().size());
    assertThat(repository.rules().stream().filter(Rule::template)).isEmpty();

    assertStreamRepeatKeyRuleProperties(repository);
  }

  private static void assertStreamRepeatKeyRuleProperties(Repository repository) {
    Rule rule = repository.rule("StreamRepeatKeyRule");
    assertThat(rule).isNotNull();
    assertThat(rule.name()).isEqualTo("java流集合转Map，预防重复Key的情况");
    assertThat(rule.type()).isEqualTo(RuleType.BUG);
  }


}
