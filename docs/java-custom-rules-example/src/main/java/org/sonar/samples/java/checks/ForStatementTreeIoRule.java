package org.sonar.samples.java.checks;

import org.sonar.check.Rule;
import org.sonar.java.model.expression.AssessableExpressionTree;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Rule(key = "ForStatementTreeIoRule")
public class ForStatementTreeIoRule extends IssuableSubscriptionVisitor {

  private static List<String> io_key_words = new ArrayList<>();

  static {
    io_key_words.add(".*jedis.*");
    io_key_words.add(".+client");
    io_key_words.add(".+Dao");
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return Stream.of(Tree.Kind.FOR_STATEMENT, Tree.Kind.FOR_EACH_STATEMENT, Tree.Kind.WHILE_STATEMENT).collect(Collectors.toList());
  }

  /**
   * Tree.is(Kind ... kind)   判断是哪种节点
   * <p>
   * 监控方法调用
   *
   * @param parentTree
   */
  @Override
  public void visitNode(Tree parentTree) {
    StatementTree body = (StatementTree) parentTree;
    // 检查循环体内的语句
    body.accept(new BaseTreeVisitor() {
      @Override
      public void visitMethodInvocation(MethodInvocationTree mit) {
        checkForIOOperation(parentTree, mit);
        super.visitMethodInvocation(mit);
      }
    });
  }

  private void checkForIOOperation(Tree parentTree, MethodInvocationTree mit) {
    String operationName = null;
    if ((operationName = getIOOperationName(mit)) != null) {
      reportIssue(parentTree, "循环内发现疑似IO操作，IO操作为: " + operationName);
    } else {
      // 递归检查方法调用内部是否包含IO操作
      Symbol methodSymbol = mit.symbol();
      if (methodSymbol.isUnknown()) {
        return;
      }

      Tree methodDeclaration = methodSymbol.declaration();
      if (methodDeclaration != null && methodDeclaration.is(Tree.Kind.METHOD)) {
        BlockTree methodBody = ((MethodTree) methodDeclaration).block();
        if (methodBody != null) {
          methodBody.accept(new BaseTreeVisitor() {
            @Override
            public void visitMethodInvocation(MethodInvocationTree innerMit) {
              checkForIOOperation(parentTree, innerMit);
              super.visitMethodInvocation(innerMit);
            }
          });
        }
      }
    }
  }

  /**
   * 判断方法内部是不是IO操作
   *
   * @param mit
   * @return
   */
  private String getIOOperationName(MethodInvocationTree mit) {
    // 检查方法调用的全路径类名，以确定是否涉及IO操作
    AssessableExpressionTree expressionTree = (AssessableExpressionTree) mit.methodSelect();

    String invokeName = getInvokeNameMethodName(expressionTree);
    System.out.println("方法调用:" + invokeName);
    for (String ioKeyWord : io_key_words) {
      if (invokeName.matches(ioKeyWord)) {
        return invokeName;
      }
    }
    return null;
  }

  /**
   * 获取方法调用的对象以及方法名
   *
   * @param expressionTree
   * @return
   */
  public String getInvokeNameMethodName(AssessableExpressionTree expressionTree) {
    StringBuilder str = new StringBuilder();
    List<Tree> children = expressionTree.getChildren();
    for (Tree child : children) {
      str.append(child.firstToken().text());
    }
    return str.toString();
  }
}
