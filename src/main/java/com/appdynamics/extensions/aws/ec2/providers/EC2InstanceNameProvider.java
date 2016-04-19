package com.appdynamics.extensions.aws.ec2.providers;

import static com.appdynamics.extensions.aws.Constants.DEFAULT_THREAD_TIMEOUT;
import static com.appdynamics.extensions.aws.util.AWSUtil.createAWSClientConfiguration;
import static com.appdynamics.extensions.aws.util.AWSUtil.createAWSCredentials;
import static com.appdynamics.extensions.aws.validators.Validator.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.Tag;
import com.appdynamics.extensions.aws.config.Account;
import com.appdynamics.extensions.aws.config.CredentialsDecryptionConfig;
import com.appdynamics.extensions.aws.config.ProxyConfig;
import com.appdynamics.extensions.aws.ec2.config.Ec2InstanceNameConfig;

/**
 * @author Florencio Sarmiento
 *
 */
public class EC2InstanceNameProvider {
	
	private static final Logger LOGGER = Logger.getLogger("com.singularity.extensions.aws.ec2.EC2InstanceNameProvider");
	
	private AtomicReference<List<Account>> accounts = new AtomicReference<List<Account>>();
	
	private AtomicReference<CredentialsDecryptionConfig> credentialsDecryptionConfig = 
			new AtomicReference<CredentialsDecryptionConfig>();
	
	private AtomicReference<ProxyConfig> proxyConfig = 
			new AtomicReference<ProxyConfig>();
	
	private AtomicReference<Ec2InstanceNameConfig> ec2InstanceNameConfig = 
			new AtomicReference<Ec2InstanceNameConfig>();
	
	private Map<String, InstanceNameDictionary> accountInstancesDictionaries = 
			new ConcurrentHashMap<String, InstanceNameDictionary>();
	
	private volatile int maxErrorRetrySize;

	private static final int SLEEP_TIME_IN_MINS = 5;
	
	private static final String EC2_REGION_ENDPOINT = "ec2.%s.amazonaws.com";
	
	private AtomicBoolean initialised = new AtomicBoolean(false);
	
	private ExecutorService ec2WorkerPool = Executors.newFixedThreadPool(3);
	
	private static EC2InstanceNameProvider instance;
	
	private EC2InstanceNameProvider() {}
	
	public static EC2InstanceNameProvider getInstance() {
		if (instance == null) {
			instance = new EC2InstanceNameProvider();
		}
		
		return instance;
	}
	
	public void initialise(List<Account> accounts, 
			CredentialsDecryptionConfig credentialsDecryptionConfig,
			ProxyConfig proxyConfig,
			Ec2InstanceNameConfig ec2InstanceNameConfig,
			int maxErrorRetrySize) {
		
		this.accounts.set(accounts);
		this.credentialsDecryptionConfig.set(credentialsDecryptionConfig);
		this.proxyConfig.set(proxyConfig);
		this.ec2InstanceNameConfig.set(ec2InstanceNameConfig);
		this.maxErrorRetrySize = maxErrorRetrySize;
		
		if (!initialised.get() && this.ec2InstanceNameConfig.get().isUseInstanceName()) {
			LOGGER.info("Initialiasing EC2 instance names...");
			retrieveInstances();
			initiateBackgroundTask(SLEEP_TIME_IN_MINS);
			initialised.set(true);
		}
	}
	
	/**
	 * Instance name should be available post initialisation {@link EC2InstanceNameProvider#initialise}.
	 * 
	 * <p>It's possible that a new instance is created post this, so name wouldn't have been retrieved yet.
	 * Background task may pick this up (depends on timing), but if not or for whatever reason the instance name 
	 * isn't available at the time, this method will attempt to retrieve it and return if available. 
	 * Otherwise, the name is set the same as the ID, until the background task runs again to refresh it.
	 * 
	 * @param accountName - the accountName
	 * @param region - the region
	 * @param instanceId - the instanceId
	 * @return the instanceName
	 */
	public String getInstanceName(String accountName, String region, String instanceId) {
		String instanceName = null;
		
		if (this.ec2InstanceNameConfig.get().isUseInstanceName()) {
			InstanceNameDictionary accountInstancesDictionary = getAccountInstanceNameDictionary(accountName);
			instanceName = accountInstancesDictionary.getEc2Instaces().get(instanceId);
			
			if (StringUtils.isBlank(instanceName)) {
				try {
					retrieveInstancesPerAccountPerRegion(getAccount(accountName), region, 
							accountInstancesDictionary, instanceId);
					
				} catch (Exception e) {
					LOGGER.error(String.format("Error while retrieving instance name for Account [%s] Region [%s]",
							accountName, region), e);
				}
				
				instanceName = accountInstancesDictionary.getEc2Instaces().get(instanceId);
				
				if (StringUtils.isBlank(instanceName)) {
					if (LOGGER.isTraceEnabled()) {
						LOGGER.trace("No name found for ec2 instance with id " + instanceId);
					}
					
					accountInstancesDictionary.addEc2Instance(instanceId, instanceId);
					instanceName = instanceId;
				}
			}
			
		} else {
			instanceName = instanceId;
		}
		
		return instanceName;
	}
	
	private void initiateBackgroundTask(long delay) {
		LOGGER.info("Initiating background task...");
		
		ScheduledExecutorService service = Executors.newScheduledThreadPool(1);
		service.scheduleAtFixedRate(
				new Runnable() {
					public void run() {
						retrieveInstances();
					}
				},
				delay, SLEEP_TIME_IN_MINS, TimeUnit.MINUTES);
	}
	
	private void retrieveInstances() {
		LOGGER.info("Retrieving ec2 instances' names...");
		
		CompletionService<Void> parallelTasksService = 
				new ExecutorCompletionService<Void>(ec2WorkerPool);
		
		int noOfTasks = 0;
		
		for (Account account : accounts.get()) {
			try {
				validateAccount(account);
				InstanceNameDictionary accountInstancesDictionary = getAccountInstanceNameDictionary(account.getDisplayAccountName());
				noOfTasks += addParallelTask(parallelTasksService, account, accountInstancesDictionary);
				
			} catch (IllegalArgumentException e) {
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug(String.format("Issue while creating task for Account [%s]", 
							account.getDisplayAccountName()), e);
				}
			} 
		}
		
		checkAllTasksCompleted(parallelTasksService, noOfTasks);
	}
	
	private void checkAllTasksCompleted(CompletionService<Void> parallelTasksService, int noOfTasks) {
		for (int index=0; index<noOfTasks; index++) {
			try {
				parallelTasksService.take().get(DEFAULT_THREAD_TIMEOUT, TimeUnit.SECONDS);
				
			} catch (InterruptedException e) {
				LOGGER.error("Task interrupted. ", e);
			} catch (ExecutionException e) {
				LOGGER.error("Task execution failed. ", e);
			} catch (TimeoutException e) {
				LOGGER.error("Task timed out. ", e);
			}
		}
	}
	
	private int addParallelTask(CompletionService<Void> parallelTasksService, 
			final Account account, 
			final InstanceNameDictionary accountInstancesDictionary) {
		
		int count = 0;
		
		for (final String region : account.getRegions()) {
			try {
				parallelTasksService.submit(new Callable<Void>() {
					public Void call() throws Exception {
						retrieveInstancesPerAccountPerRegion(account, region, accountInstancesDictionary);
						return null;
					}
				});
				
				++count;
			} catch (Exception e) {
				LOGGER.error(String.format("Error while retrieving EC2 instances for Account [%s] Region [%s]",
						account.getDisplayAccountName(), region), e);
			}
		}
		
		return count;
	}
	
	private InstanceNameDictionary getAccountInstanceNameDictionary(String accountName) {
		InstanceNameDictionary accountInstancesDictionary = 
				accountInstancesDictionaries.get(accountName);
		
		if (accountInstancesDictionary == null) {
			accountInstancesDictionary = new InstanceNameDictionary();
			accountInstancesDictionaries.put(accountName, accountInstancesDictionary);
		}
		
		return accountInstancesDictionary;
	}
	
	private void retrieveInstancesPerAccountPerRegion(Account account, String region, 
			InstanceNameDictionary accountInstanceNameDictionary, String... instanceIds) {
		AWSCredentials awsCredentials = createAWSCredentials(account, credentialsDecryptionConfig.get());
		ClientConfiguration awsClientConfig = createAWSClientConfiguration(maxErrorRetrySize, proxyConfig.get());
		
		AmazonEC2Client ec2Client = new AmazonEC2Client(awsCredentials, awsClientConfig);
		ec2Client.setEndpoint(String.format(EC2_REGION_ENDPOINT, region));
		
		Filter filter = new Filter();
		filter.setName(ec2InstanceNameConfig.get().getTagFilterName());
		filter.setValues(Arrays.asList(ec2InstanceNameConfig.get().getTagKey()));
		
		DescribeInstancesRequest request = new DescribeInstancesRequest();
		request.setFilters(Arrays.asList(filter));
		
		if (instanceIds != null && instanceIds.length > 0) {
			request.setInstanceIds(Arrays.asList(instanceIds));
		}
		
		DescribeInstancesResult result = ec2Client.describeInstances(request);
		
		if (result != null) {
			List<Reservation> reservations = result.getReservations();
			
			while (StringUtils.isNotBlank(result.getNextToken())) {
				request.setNextToken(result.getNextToken());
				result = ec2Client.describeInstances(request);
				reservations.addAll(result.getReservations());
			}
			
			for (Reservation reservation : result.getReservations()) {
				for (Instance instance : reservation.getInstances()) {
					accountInstanceNameDictionary.addEc2Instance(instance.getInstanceId(), 
							getInstanceName(instance.getInstanceId(), instance.getTags()));
				}
			}
		}
	}
	
	private String getInstanceName(String defaultValue, List<Tag> tags) {
		for (Tag tag : tags) {
			if (ec2InstanceNameConfig.get().getTagKey().equals(tag.getKey())) {
				if (StringUtils.isNotBlank(tag.getValue())) {
					return tag.getValue();
				}
				
				break;
			}
		}
		
		return defaultValue;
	}
	
	private Account getAccount(String accountName) {
		for (Account account : accounts.get()) {
			if (account.getDisplayAccountName().equalsIgnoreCase(accountName)) {
				return account;
			}
		}
		
		return null;
	}
}