package org.sonar.samples.java.checks;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.sonar.check.Rule;
import org.sonar.java.model.expression.AssessableExpressionTree;
import org.sonar.java.model.expression.IdentifierTreeImpl;
import org.sonar.java.model.expression.MemberSelectExpressionTreeImpl;
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
    io_key_words.add(".*redis.*");
    io_key_words.add(".*Redis.*");

    io_key_words.add(".*Jedis.*");
    io_key_words.add(".*jedis.*");

    io_key_words.add(".+Client");
    io_key_words.add(".+client");

    io_key_words.add(".+Dao");
    io_key_words.add(".+Mapper");
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return Stream.of(Tree.Kind.FOR_STATEMENT,
      Tree.Kind.FOR_EACH_STATEMENT,
      Tree.Kind.DO_STATEMENT,
      Tree.Kind.WHILE_STATEMENT
    ).collect(Collectors.toList());
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
   * 获取循环内部可能出现IO操作的方法名称
   *
   * @param mit
   * @return
   */
  private String getIOOperationName(MethodInvocationTree mit) {
    // 检查方法调用的全路径类名，以确定是否涉及IO操作
    ExpressionTree expressionTree = mit.methodSelect();
    List<Tree> children = Collections.emptyList();
    if (expressionTree.is(Tree.Kind.MEMBER_SELECT)) {
      MemberSelectExpressionTreeImpl memberSelectExpressionTree = (MemberSelectExpressionTreeImpl) expressionTree;
      children = memberSelectExpressionTree.children();
    } else if (expressionTree.is(Tree.Kind.IDENTIFIER)) {
      IdentifierTreeImpl identifierTreeImpl = (IdentifierTreeImpl) expressionTree;
      children = identifierTreeImpl.children();
    } else {
      System.out.println("发现未知的成员方法类:" + expressionTree);
      return null;
    }
    String invokeName = getInvokeNameMethodName(children);
    // System.out.println("方法调用:" + invokeName);
    // 只看对象形参名称，方法名不重要
    String[] invokeNameArray = invokeName.split("\\.");
    for (String ioKeyWord : io_key_words) {
      if (invokeNameArray[0].matches(ioKeyWord)) {
        System.out.println("发现循环调用方法:" + invokeName);
        return invokeName;
      }
    }
    return null;
  }

  /**
   * 获取方法调用的对象以及方法名
   *
   * @param trees
   * @return
   */
  public String getInvokeNameMethodName(List<Tree> trees) {
    StringBuilder str = new StringBuilder();
    for (Tree child : trees) {
      str.append(child.firstToken().text());
    }
    return str.toString();
  }
}
