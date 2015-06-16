class Foo {
  @Override
  protected void finalize() throws Throwable {  // Compliant
    System.out.println("foo");
    super.finalize();
  }

  @Override
  protected void finalize() throws Throwable {
    super.finalize();                           // Noncompliant {{Move this super.finalize() call to the end of this Object.finalize() implementation.}}
    System.out.println("foo");
  }

  @Override
  protected void finalize() throws Throwable {  // Noncompliant {{Add a call to super.finalize() at the end of this Object.finalize() implementation.}}
  }

  @Override
  protected void finalize() throws Throwable {  // Noncompliant
    System.out.println("foo");
    super.foo();
  }

  @Override
  protected void foo() throws Throwable {       // Compliant
  }

  boolean finalize() {                          // Compliant
  }

  void finalize() {
    if (0) {
      super.finalize();
    } else {
      super.finalize();                         // Noncompliant
    }
  }

  void finalize() {
    try {
      // ...
    } finally {
      super.finalize();                         // Compliant
    }

    int a;
  }

  void finalize() {
    try {
      // ...
    } finally {
      super.finalize();                         // Noncompliant
      System.out.println();
    }
  }

  void finalize() {
    try {
      // ...
    } catch (Exception e) {
      super.finalize();                         // Noncompliant
    }
  }
  public void finalize(Object pf, int mode) {

  }
}
