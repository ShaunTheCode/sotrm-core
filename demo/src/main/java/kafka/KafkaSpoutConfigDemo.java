package kafka;

import org.apache.storm.Config;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.kafka.spout.KafkaSpoutConfig;
import org.apache.storm.topology.TopologyBuilder;

public class KafkaSpoutConfigDemo {
    private final static String BOOTSTRAP_SERVER = "10.68.194.163:21005,10.68.192.218:21005,10.68.195.51:21005,10.68.193.251:21005,10.68.194.5:21005,10.68.192.160:21005,10.68.195.10:21005,10.68.192.89:21005,10.68.193.58:21005,10.68.194.87:21005,10.68.192.211:21005,10.68.195.239:21005,10.68.192.157:21005,10.68.193.126:21005,10.68.194.54:21005,10.68.195.197:21005,10.68.192.196:21005,10.68.193.182:21005,10.68.193.64:21005,10.68.192.185:21005,10.68.193.218:21005,10.68.195.46:21005,10.68.193.69:21005,10.68.194.244:21005,10.68.193.205:21005,10.68.195.23:21005,10.68.194.110:21005,10.68.194.143:21005";

    private final static String TOPIC = "voc_ds_ecom_cn";

    public static void main(String[] args) {
        TopologyBuilder builder =new TopologyBuilder();

        builder.setSpout("consume-kafka",getKafkaSpout(),8);

        builder.setBolt("sout-message",new SoutMessageBolt(),1).localOrShuffleGrouping("consume-kafka");

        final LocalSubmitter localSubmitter = LocalSubmitter.newInstance();


        Config config = new Config();
        // 10分钟 超时
        config.put(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS, 60 * 10);
        config.put(Config.TOPOLOGY_MAX_SPOUT_PENDING, 32768);

        localSubmitter.submit("test",config,builder.createTopology());


        System.out.println("demo");

    }


    private static KafkaSpout<String, String> getKafkaSpout() {
        KafkaSpoutConfig<String, String> topic = KafkaSpoutConfig.builder(BOOTSTRAP_SERVER, TOPIC).build();
        return new KafkaSpout<>(topic);
    }
}
