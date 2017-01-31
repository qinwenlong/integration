package partition;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.integration.partition.MessageChannelPartitionHandler;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.BeanPropertyItemSqlParameterSourceProvider;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.Aggregator;
import org.springframework.integration.annotation.MessageEndpoint;
import org.springframework.integration.annotation.Payloads;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.jdbc.core.JdbcOperations;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//@Configuration
public class PartitionConfiguration {

	public static final String LEADER_PROFILE = "!worker";
	public static final String STEP_1 = "step1";
//	public static final String WORKER_STEP = "workerStep";


}

@Configuration
class WorkerStepConfiguration {

	private Log log = LogFactory.getLog(getClass());

	@Value("${partition.chunk-size}")
	private int chunk;

	@Bean
	@StepScope
	JdbcPagingItemReader<Person> reader(DataSource dataSource,
	                                    @Value("#{stepExecutionContext['minValue']}") Long minValue,
	                                    @Value("#{stepExecutionContext['maxValue']}") Long maxValue) {

		log.info("reading " + minValue + " to " + maxValue);

		MySqlPagingQueryProvider queryProvider = new MySqlPagingQueryProvider();
		queryProvider.setSelectClause("id as id, email as email, age as age, first_name as firstName");
		queryProvider.setFromClause("from PEOPLE");
		queryProvider.setWhereClause("where id >= " + minValue + " and id <= " + maxValue);
		queryProvider.setSortKeys(Collections.singletonMap("id", Order.ASCENDING));

		JdbcPagingItemReader<Person> reader = new JdbcPagingItemReader<>();
		reader.setDataSource(dataSource);
		reader.setFetchSize(this.chunk);
		reader.setQueryProvider(queryProvider);
		reader.setRowMapper((rs, i) -> new Person(
				rs.getInt("id"),
				rs.getInt("age"),
				rs.getString("firstName"),
				rs.getString("email")));
		return reader;
	}

	@Bean
	@StepScope
	JdbcBatchItemWriter<Person> writer(DataSource dataSource) {
		JdbcBatchItemWriter<Person> writer = new JdbcBatchItemWriter<>();
		writer.setItemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider<>());
		writer.setSql("INSERT INTO NEW_PEOPLE(id,age,first_name,email) VALUES(:id, :age, :firstName, :email )");
		writer.setDataSource(dataSource);
		return writer;
	}


	@Bean
	Step workerStep(StepBuilderFactory stepBuilderFactory) {
		return stepBuilderFactory
				.get("workerStep")
				.<Person, Person>chunk(this.chunk)
				.reader(reader(null, null, null))
				.writer(writer(null))
				.build();
	}

}

@Configuration
class PartitionStepConfiguration {

	@Value("${partition.grid-size:2}")
	private int gridSize;

	@Bean
	MessagingTemplate messagingTemplate(PartitionLeaderChannels channels) {
		MessagingTemplate messagingTemplate = new MessagingTemplate(
				channels.leaderRequestsChannel());
		messagingTemplate.setReceiveTimeout(60 * 1000 * 60);
		return messagingTemplate;
	}

	@MessageEndpoint
	public static class ReplyAggregatingMessageEndpoint {

		@Autowired
		private MessageChannelPartitionHandler partitionHandler;

		@Aggregator(inputChannel = PartitionLeaderChannels.LEADER_REPLIES,
				outputChannel = PartitionLeaderChannels.LEADER_REPLIES_AGGREGATED,
				sendTimeout = "3600000", sendPartialResultsOnExpiry = "true")
		public List<?> aggregate(@Payloads List<?> messages) {
			return this.partitionHandler.aggregate(messages);
		}
	}

	@Bean
	MessageChannelPartitionHandler partitionHandler(
			MessagingTemplate messagingTemplate,
			JobExplorer jobExplorer,
			PartitionLeaderChannels master) throws Exception {
		MessageChannelPartitionHandler partitionHandler = new MessageChannelPartitionHandler();
		partitionHandler.setReplyChannel(master.leaderRequestsAggregatedChannel());
		partitionHandler.setMessagingOperations(messagingTemplate);
		partitionHandler.setJobExplorer(jobExplorer);
		partitionHandler.setStepName("workerStep");
		partitionHandler.setPollInterval(5_000L);
		partitionHandler.setGridSize(this.gridSize);
		return partitionHandler;
	}

	@Bean
	Partitioner partitioner(JdbcOperations jdbcTemplate,
	                        @Value("${partition.table:PEOPLE}") String table,
	                        @Value("${partition.column:ID}") String column) {
		return gridSize -> {

			Map<String, ExecutionContext> result = new HashMap<>();

			int min = jdbcTemplate.queryForObject("SELECT MIN(" + column
					+ ") from " + table, Integer.class);
			int max = jdbcTemplate.queryForObject("SELECT MAX(" + column
					+ ") from " + table, Integer.class);
			int targetSize = (max - min) / gridSize + 1;
			int number = 0;
			int start = min;
			int end = start + targetSize - 1;

			while (start <= max) {
				ExecutionContext value = new ExecutionContext();
				result.put("partition" + number, value);
				if (end >= max) {
					end = max;
				}
				value.putInt("minValue", start);
				value.putInt("maxValue", end);
				start += targetSize;
				end += targetSize;
				number++;
			}

			return result;
		};
	}


	@Bean
	Step partitionStep(StepBuilderFactory sbf,
	                   Partitioner partitioner,
	                   PartitionHandler partitionHandler,
	                   WorkerStepConfiguration workerStepConfiguration) throws Exception {
		Step step = workerStepConfiguration.workerStep(null);
		return sbf.get("partitionStep")
				.partitioner(step.getName(), partitioner)
				.step(step)
				.partitionHandler(partitionHandler)
				.build();
	}
}