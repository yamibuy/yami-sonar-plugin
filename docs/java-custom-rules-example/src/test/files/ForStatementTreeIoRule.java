import com.yamibuy.ec.customer.common.JedisClient;

@Service
public class ForStatementTreeRule {

  @Autowired
  OrderClient orderClient;

  void foo1() {
    for (int i = 0; i < 10000; i++) {
      setValue(i);
    }
  }

  void setValue(int i) {
    setValue2(i);
  }

  void setValue2(int i) {
    orderClient.updateSessionId(null);
  }

  void foo2() {
    while (true) {
      orderClient.updateSessionId(null);
    }
  }

  @FeignClient(name = "${feign.ec-so.name:}", url = "${feign.ec-so.url:}")
  static class OrderClient {

    @Autowired
    JedisClient jedisClient;
    @RequestMapping(value = "/pay/session_id", method = RequestMethod.PUT)
    public void updateSessionId(@RequestBody OrderInfo orderInfo){
      jedisClient.set("a", i);
    }
  }
}
