package kafka;

import org.apache.storm.Config;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.kafka.spout.KafkaSpoutConfig;
import org.apache.storm.topology.TopologyBuilder;

public class KafkaSpoutConfigDemo {
    private final static String BOOTSTRAP_SERVER = "localhost:9092";
    private final static String TOPIC = "test";

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
