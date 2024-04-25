import java.util.Stream.*;
import java.util.*;

class StreamRepeatKeyRule {
  MyClass(MyClass mc) { }

  static void foo1(){
    List<User> stream = new ArrayList<>();
    Map<String, User> mapValue2 = stream.stream().collect(Collectors.toMap(User::getName,User::getName));
  }

  static void foo2(){
    List<User> stream = new ArrayList<>();
    Map<String, User> mapValue2 = stream.stream().collect(Collectors.toMap(User::getName, Function.identity(), (key1, ke2) -> key1));
  }

  public static class User{

    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }
}
