import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.kafka.spout.KafkaSpoutConfig;

public class KafkaSpoutConfigDemo {
    public static void main(String[] args) {
        KafkaSpoutConfig<String, String> topic = KafkaSpoutConfig.builder("127.0.0.1", "topic").build();
        KafkaSpout<String, String> objectObjectKafkaSpoutConfig = new KafkaSpout<>(topic);

        System.out.println("demo");

    }
}
