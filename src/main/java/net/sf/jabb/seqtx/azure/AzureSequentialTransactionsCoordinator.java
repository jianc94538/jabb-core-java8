/**
 * 
 */
package net.sf.jabb.seqtx.azure;

import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jabb.azure.AzureStorageUtility;
import net.sf.jabb.seqtx.SimpleSequentialTransaction;
import net.sf.jabb.seqtx.SequentialTransaction;
import net.sf.jabb.seqtx.ReadOnlySequentialTransaction;
import net.sf.jabb.seqtx.SequentialTransactionsCoordinator;
import net.sf.jabb.seqtx.ex.DuplicatedTransactionIdException;
import net.sf.jabb.seqtx.ex.IllegalTransactionStateException;
import net.sf.jabb.seqtx.ex.InfrastructureErrorException;
import net.sf.jabb.seqtx.ex.NoSuchTransactionException;
import net.sf.jabb.seqtx.ex.NotOwningTransactionException;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.table.CloudTable;
import com.microsoft.azure.storage.table.CloudTableClient;
import com.microsoft.azure.storage.table.DynamicTableEntity;
import com.microsoft.azure.storage.table.TableBatchOperation;
import com.microsoft.azure.storage.table.TableOperation;
import com.microsoft.azure.storage.table.TableQuery;
import com.microsoft.azure.storage.table.TableQuery.QueryComparisons;
import com.microsoft.azure.storage.table.TableRequestOptions;
import com.microsoft.azure.storage.table.TableServiceException;

/**
 * The implementation of SequentialTransactionsCoordinator that is backed by Microsoft Azure table storage.
 * The existence of the underlying table is checked and ensured only once during the life time of the instance of this class.
 * @author James Hu
 *
 */
public class AzureSequentialTransactionsCoordinator implements SequentialTransactionsCoordinator {
	static private final Logger logger = LoggerFactory.getLogger(AzureSequentialTransactionsCoordinator.class);

	public static final String DEFAULT_TABLE_NAME = "SequentialTransactionsCoordinator";
	protected String tableName = DEFAULT_TABLE_NAME;
	protected CloudTableClient tableClient;
	
	protected volatile SimpleSequentialTransaction lastSucceededTransactionCached;
	protected volatile boolean tableExists = false;
	
	
	public AzureSequentialTransactionsCoordinator(){
		
	}
	
	public AzureSequentialTransactionsCoordinator(CloudStorageAccount storageAccount, String tableName, Consumer<TableRequestOptions> defaultOptionsConfigurer){
		this();
		if (tableName != null){
			this.tableName = tableName;
		}
		tableClient = storageAccount.createCloudTableClient();
		if (defaultOptionsConfigurer != null){
			defaultOptionsConfigurer.accept(tableClient.getDefaultRequestOptions());
		}
	}
	
	public AzureSequentialTransactionsCoordinator(CloudStorageAccount storageAccount, String tableName){
		this(storageAccount, tableName, null);
	}

	public AzureSequentialTransactionsCoordinator(CloudStorageAccount storageAccount, Consumer<TableRequestOptions> defaultOptionsConfigurer){
		this(storageAccount, null, defaultOptionsConfigurer);
	}

	public AzureSequentialTransactionsCoordinator(CloudStorageAccount storageAccount){
		this(storageAccount, null, null);
	}
	
	public AzureSequentialTransactionsCoordinator(CloudTableClient tableClient, String tableName){
		this();
		if (tableName != null){
			this.tableName = tableName;
		}
		this.tableClient = tableClient;
	}

	public AzureSequentialTransactionsCoordinator(CloudTableClient tableClient){
		this(tableClient, null);
	}


	public void setTableName(String tableName) {
		this.tableName = tableName;
	}

	public void setTableClient(CloudTableClient tableClient) {
		this.tableClient = tableClient;
	}

	@Override
	public SequentialTransaction startTransaction(String seriesId,
			String previousTransactionId,
			ReadOnlySequentialTransaction transaction,
			int maxInProgressTransacions, int maxRetryingTransactions)
			throws InfrastructureErrorException,
			DuplicatedTransactionIdException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void finishTransaction(String seriesId, String processorId,
			String transactionId, String endPosition) throws NotOwningTransactionException,
			InfrastructureErrorException, IllegalTransactionStateException,
			NoSuchTransactionException {
		// update a single transaction
		
	}

	@Override
	public void abortTransaction(String seriesId, String processorId,
			String transactionId) throws NotOwningTransactionException,
			InfrastructureErrorException, IllegalTransactionStateException,
			NoSuchTransactionException {
		// update a single transaction
		
	}

	@Override
	public void renewTransactionTimeout(String seriesId, String processorId,
			String transactionId, Instant timeout)
			throws NotOwningTransactionException, InfrastructureErrorException,
			IllegalTransactionStateException, NoSuchTransactionException {
		// update a single transaction
		
	}

	@Override
	public boolean isTransactionSuccessful(String seriesId,
			String transactionId, Instant beforeWhen)
			throws InfrastructureErrorException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public List<? extends ReadOnlySequentialTransaction> getRecentTransactions(
			String seriesId) throws InfrastructureErrorException {
		// get entities by seriesId
		Map<String, SequentialTransactionWrapper> wrappedTransactionEntities = fetchEntities(seriesId, true);
		LinkedList<SequentialTransactionWrapper> transactionEntities = toList(wrappedTransactionEntities);
		
		// compact the list
		compact(transactionEntities);
		
		return transactionEntities.stream().map(SequentialTransactionWrapper::getTransactionNotNull).collect(Collectors.toList());
	}

	@Override
	public void clear(String seriesId) throws InfrastructureErrorException {
		// delete entities by seriesId
		try{
			CloudTable table = getTableReference();
			AzureStorageUtility.deleteEntitiesIfExist(table, 
					TableQuery.generateFilterCondition(
							AzureStorageUtility.PARTITION_KEY, 
							QueryComparisons.EQUAL,
							seriesId));
		}catch(Exception e){
			throw new InfrastructureErrorException("Failed to delete entities belonging to series '" + seriesId + "' in table " + tableName, e);
		}
	}

	@Override
	public void clearAll() throws InfrastructureErrorException {
		// delete all entities
		try{
			CloudTable table = getTableReference();
			AzureStorageUtility.deleteEntitiesIfExist(table, (String)null);
		}catch(Exception e){
			throw new InfrastructureErrorException("Failed to delete all entities in table " + tableName, e);
		}
	}
	
	protected CloudTable getTableReference() throws InfrastructureErrorException{
		CloudTable table;
		try {
			table = tableClient.getTableReference(tableName);
		} catch (Exception e) {
			throw new InfrastructureErrorException("Failed to get reference for table '" + tableName + "'", e);
		}
		if (!tableExists){
			try {
				AzureStorageUtility.createIfNotExist(tableClient, tableName);
			} catch (Exception e) {
				throw new InfrastructureErrorException("Failed to ensure the existence of table '" + tableName + "'", e);
			}
			tableExists = true;
		}
		return table;
	}
	
	/**
	 * Remove succeeded from the head and leave only one, transit those timed out to TIMED_OUT state,
	 * and remove the last transaction if it is a failed one with a null end position.
	 * @param transactionEntities	 The list of transaction entities. The list may be changed inside this method.
	 * @throws InfrastructureErrorException 
	 */
	protected void compact(LinkedList<SequentialTransactionWrapper> transactionEntities) throws InfrastructureErrorException{
		// remove finished historical transactions and leave only one of them
		int finished = 0;
		Iterator<SequentialTransactionWrapper> iterator = transactionEntities.iterator();
		if (iterator.hasNext()){
			SequentialTransactionWrapper wrapper;
			wrapper = iterator.next();
			wrapper.updateFromEntity();
			if (wrapper.getTransaction().isFinished()){
				finished ++;
				while(iterator.hasNext()){
					wrapper = iterator.next();
					wrapper.updateFromEntity();
					if (wrapper.getTransaction().isFinished()){
						finished ++;
					}else{
						break;
					}
				}
			}
		}
		
		CloudTable table = getTableReference();
		while (finished -- > 1){
			SequentialTransactionWrapper first = transactionEntities.getFirst();
			SequentialTransactionWrapper second = first.next;
			
			// do in a transaction: remove the first one, and update the second one
			TableBatchOperation batchOperation = new TableBatchOperation();
			batchOperation.add(TableOperation.delete(first.getEntity()));
			second.setFirstTransaction();
			batchOperation.add(TableOperation.replace(second.getEntity()));
			try{
				AzureStorageUtility.executeIfExist(table, batchOperation);
			}catch(StorageException e){
				throw new InfrastructureErrorException("Failed to remove succeeded transaction entity with keys '" + first.entityKeysToString() 
						+ "' and make the next entity with keys '" + second.entityKeysToString() 
						+ "' the new first one, probably one of them has been modified by another client.", e);
			}
			transactionEntities.removeFirst();
		}
		
		// update lastSucceededTransactionCached
		if (transactionEntities.size() > 0){
			SequentialTransactionWrapper first = transactionEntities.getFirst();
			if (first.getTransactionNotNull().isFinished()){
				this.lastSucceededTransactionCached = SimpleSequentialTransaction.copyOf(first.getTransaction());
			}
		}
		
		// handle time out
		Instant now = Instant.now();
		for (SequentialTransactionWrapper wrapper: transactionEntities){
			SimpleSequentialTransaction tx = wrapper.getTransactionNotNull();
			if (tx.isInProgress() && tx.getTimeout().isBefore(now)){
				if (tx.timeout()){
					wrapper.updateToEntity();
					try{
						table.execute(TableOperation.replace(wrapper.getEntity()));
					}catch(StorageException e){
						throw new InfrastructureErrorException("Failed to update timed out transaction entity with keys '" + wrapper.entityKeysToString() 
								+ "', probably it has been modified by another client.", e);
					}
				}else{
					throw new IllegalStateException("Transaction '" + tx.getTransactionId() + "' in series '" + wrapper.getSeriesId() 
							+ "' is currently in " + tx.getState() + " state and cannot be changed to TIMED_OUT state");
				}
			}
		}
		
		// if the last transaction is failed and is open, remove it
		if (transactionEntities.size() > 0){
			SequentialTransactionWrapper wrapper = transactionEntities.getLast();
			SimpleSequentialTransaction tx = wrapper.getTransactionNotNull();
			if (tx.isFailed() && tx.getEndPosition() == null){
				try {
					AzureStorageUtility.deleteEntitiesIfExist(table, wrapper.getEntity());
				} catch (StorageException e) {
					throw new InfrastructureErrorException("Failed to delete failed open range transaction entity with keys '" + wrapper.entityKeysToString() 
							+ "', probably it has been modified by another client.", e);
				}
				transactionEntities.removeLast();
			}
		}
	}
	
	/**
	 * Fetch all entities belonging to a series into a map of SequentialTransactionEntityWrapper indexed by transaction ID
	 * @param seriesId								the ID of the series
	 * @param putAdditionalFirstTransactionEntry	When true, one additional entry will be put into the result map. The entry will have a key of null, 
	 * 												and the value will be the wrapper of the first transaction.
	 * @return		a map of SequentialTransactionEntityWrapper indexed by transaction ID, if putAdditionalFirstTransactionEntry argument is true
	 * 				there will be one more additional entry for the first transaction.
	 * @throws InfrastructureErrorException
	 */
	protected Map<String, SequentialTransactionWrapper> fetchEntities(String seriesId, boolean putAdditionalFirstTransactionEntry) throws InfrastructureErrorException{
		// fetch entities by seriesId
		Map<String, SequentialTransactionWrapper> map = new HashMap<>();

		try{
			CloudTable table = getTableReference();
			TableQuery<DynamicTableEntity> query = TableQuery.from(DynamicTableEntity.class).
					where(TableQuery.generateFilterCondition(
							AzureStorageUtility.PARTITION_KEY, 
							QueryComparisons.EQUAL,
							seriesId));
			for (DynamicTableEntity entity: table.execute(query)){
				SequentialTransactionWrapper wrapper = new SequentialTransactionWrapper(entity);
				/*
				if (wrapper.isFirstTransaction()){
					wrapper.setFirstTransaction();
				}
				if (wrapper.isLastTransaction()){
					wrapper.setLastTransaction();
				}*/
				map.put(wrapper.getEntity().getRowKey(), wrapper);		// indexed by transaction id
			}
		}catch(Exception e){
			throw new InfrastructureErrorException("Failed to fetch entities belonging to series '" + seriesId + "' in table " + tableName, e);
		}
		
		// sanity check
		int numFirst = 0;
		int numLast = 0;
		for (SequentialTransactionWrapper wrapper: map.values()){
			if (wrapper.isFirstTransaction()){
				numFirst ++;
			}
			if (wrapper.isLastTransaction()){
				numLast ++;
			}
		}
		if (!(numFirst == 0 && numLast == 0 || numFirst == 1 && numLast == 1)){
			throw new InfrastructureErrorException("Corrupted data for series '" + seriesId + "' in table " + tableName 
					+ ", number of first transaction(s): " + numFirst + ", number of last transaction(s): " + numLast);
		}

		// link them up, with sanity check
		SequentialTransactionWrapper first = null;
		for (SequentialTransactionWrapper wrapper: map.values()){
			if (wrapper.isFirstTransaction()){
				first = wrapper;
			}else{
				String previousId = wrapper.getPreviousTransactionId();
				SequentialTransactionWrapper previous = map.get(previousId);
				if (previous == null){
					throw new InfrastructureErrorException("Corrupted data for series '" + seriesId + "' in table " + tableName
							+ ", previous transaction ID '" + previousId + "' of transaction '" + wrapper.getEntity().getRowKey() + "' cannot be found");
				}
				wrapper.setPrevious(previous);
			}
			if (!wrapper.isLastTransaction()){
				String nextId = wrapper.getPreviousTransactionId();
				SequentialTransactionWrapper next = map.get(nextId);
				if (next == null){
					throw new InfrastructureErrorException("Corrupted data for series '" + seriesId + "' in table " + tableName
							+ ", next transaction ID '" + nextId + "' of transaction '" + wrapper.getEntity().getRowKey() + "' cannot be found");
				}
				wrapper.setNext(next);
			}
		}
		
		if (putAdditionalFirstTransactionEntry && first != null){
			map.put(null, first);
		}
		return map;
	}
	
	/**
	 * Convert the map of SequentialTransactionEntityWrapper into a list of SequentialTransactionWrapper.
	 * @param map	the map returned by {@link #fetchEntities(String, boolean)}
	 * @return		The list, with oldest transaction as the first element and latest transaction as the last element
	 */
	protected LinkedList<SequentialTransactionWrapper> toList(Map<String, SequentialTransactionWrapper> map){
		LinkedList<SequentialTransactionWrapper> list = new LinkedList<>();
		SequentialTransactionWrapper first = map.get(null);
		if (first == null){
			Optional<SequentialTransactionWrapper> optionalFirst =
					map.values().stream().filter(wrapper -> wrapper.isFirstTransaction()).findFirst();
			first = optionalFirst.orElse(null);
		}
		if (first != null){
			SequentialTransactionWrapper wrapper = first;
			do{
				list.add(wrapper);
				wrapper = wrapper.getNext();
			}while(wrapper != null);
		}
		return list;
	}


}
