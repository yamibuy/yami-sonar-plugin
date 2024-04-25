package org.sonar.samples.java.checks;

import org.sonar.check.Rule;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.*;

import java.util.Collections;
import java.util.List;

@Rule(key = "StreamRepeatKeyRule")
public class StreamRepeatKeyRule extends IssuableSubscriptionVisitor {

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return Collections.singletonList(Tree.Kind.METHOD_INVOCATION);
  }

  /**
   * Tree.is(Kind ... kind)   判断是哪种节点
   * <p>
   * 监控方法调用
   *
   * @param visitTree
   */
  @Override
  public void visitNode(Tree visitTree) {
    MethodInvocationTree methodInvocation = (MethodInvocationTree) visitTree;
    ExpressionTree memberSelect = methodInvocation.methodSelect();
    try {
      String firstToken = memberSelect.firstToken().text();
      String lastToken = memberSelect.lastToken().text();
      String exp = firstToken + "." + lastToken;
      if ("Collectors.toMap".equals(exp)) {
        // 检查是否有处理重复 key 的情况， 简单判断，入参2个说明没有处理重复KEY情况
        if (methodInvocation.arguments().size() == 2) {
          reportIssue(visitTree, "发现 Collectors.toMap 操作没有处理重复键的情况");
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
