package org.sonar.samples.java.checks;

import org.sonar.check.Rule;
import org.sonar.java.model.declaration.MethodTreeImpl;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Rule(key = "ForStatementTreeIoRule")
public class ForStatementTreeIoRule extends IssuableSubscriptionVisitor {

  private static final int MAX_DEEP = 10;

  private static List<String> io_key_words = new ArrayList<>();

  static {
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
    AtomicInteger deepTimes = new AtomicInteger();
    StatementTree body = (StatementTree) parentTree;
    // 检查循环体内的语句
    body.accept(new BaseTreeVisitor() {
      @Override
      public void visitMethodInvocation(MethodInvocationTree mit) {
        checkForIOOperation(parentTree, mit, deepTimes);
        super.visitMethodInvocation(mit);
      }
    });
  }

  private void checkForIOOperation(Tree parentTree, MethodInvocationTree mit, AtomicInteger deepTimes) {
    String currMethod = getMethodCallName(mit);
    if (currMethod.matches(".*Enum.*")) {
      // System.out.println("枚举" + currMethod + ", 退出");
      return;
    }
    int times = deepTimes.get();
    if (times >= MAX_DEEP) {
      // System.out.println("循环递归太深入" + times + "，退出:" + currMethod);
      return;
    }
    deepTimes.getAndIncrement();
    String operationName = null;
    if ((operationName = getIOOperationName(mit)) != null) {
      String parentMethod = findParentMethod(mit);
      String issue = "循环内发现疑似IO操作，入口处为：" + parentMethod + ", IO操作为: " + operationName;
      System.out.println(issue);
      reportIssue(parentTree, issue);
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
              checkForIOOperation(parentTree, innerMit, deepTimes);
              super.visitMethodInvocation(innerMit);
            }
          });
        }
      }
    }
  }

  /**
   * 获取上一次 的方法调用
   *
   * @param mit
   * @return
   */
  private String findParentMethod(MethodInvocationTree mit) {
    try {
      Tree parent = mit;
      int times = 0;
      while (parent != null && times < 20) {
        times++;
        parent = parent.parent();
        if (parent.is(Tree.Kind.METHOD)) {
          break;
        }
      }
      MethodTree methodTree = (MethodTree) parent;
      return methodTree.simpleName().name();
    } catch (Exception e) {
      return "未知";
    }

  }

  /**
   * 获取方法调用名称
   *
   * @param mit
   * @return
   */
  private String getMethodCallName(MethodInvocationTree mit) {
    ExpressionTree expressionTree = mit.methodSelect();
    String firstText = expressionTree.firstToken().text();
    String lastText = expressionTree.lastToken().text();
    if (!firstText.equals(lastText)) {
      firstText = firstText + "." + lastText;
    }
    return firstText;
  }

  /**
   * 获取循环内部可能出现IO操作的方法名称
   *
   * @param mit
   * @return
   */
  private String getIOOperationName(MethodInvocationTree mit) {
    try {
      // 检查方法调用的全路径类名，以确定是否涉及IO操作
      String invokeName = getMethodCallName(mit);
      // System.out.println("方法调用:" + invokeName);
      // 只看对象形参名称，方法名不重要
      String[] invokeNameArray = invokeName.split("\\.");
      for (String ioKeyWord : io_key_words) {
        if (invokeNameArray[0].matches(ioKeyWord)) {
          // System.out.println("发现循环调用方法:" + invokeName);
          return invokeName;
        }
      }
      return null;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }
}
