(ns rotary.client
  "Amazon DynamoDB client functions."
  (:use [clojure.algo.generic.functor :only (fmap)])
  (:require [clojure.string :as str])
  (:import com.amazonaws.auth.BasicAWSCredentials
           com.amazonaws.services.dynamodb.AmazonDynamoDBClient
           [com.amazonaws.services.dynamodb.model
            AttributeValue
            AttributeValueUpdate
            BatchWriteItemRequest
            BatchWriteItemResult
            Condition
            CreateTableRequest
            DeleteItemRequest
            DeleteItemResult
            DeleteTableRequest
            DescribeTableRequest
            DescribeTableResult
            ExpectedAttributeValue
            GetItemRequest
            GetItemResult
            Key
            KeySchema
            KeySchemaElement
            ProvisionedThroughput
            ProvisionedThroughputDescription
            PutItemRequest
            PutItemResult
            PutRequest
            QueryRequest
            ResourceNotFoundException
            ScanRequest
            UpdateItemRequest
            UpdateTableRequest
            WriteRequest]))

(defn- db-client*
  "Get a AmazonDynamoDBClient instance for the supplied credentials."
  [cred]
  (AmazonDynamoDBClient.
   (BasicAWSCredentials. (:access-key cred) (:secret-key cred))))

(def db-client
  (memoize db-client*))

(defprotocol AsMap
  (as-map [x]))

(defn- key-schema-element
  "Create a KeySchemaElement object."
  [{key-name :name, key-type :type}]
  (doto (KeySchemaElement.)
    (.setAttributeName (str key-name))
    (.setAttributeType (str/upper-case (name key-type)))))

(defn- key-schema
  "Create a KeySchema object."
  [hash-key & [range-key]]
  (let [schema (KeySchema. (key-schema-element hash-key))]
    (when range-key
      (.setRangeKeyElement schema (key-schema-element range-key)))
    schema))

(defn- provisioned-throughput
  "Created a ProvisionedThroughput object."
  [{read-units :read, write-units :write}]
  (doto (ProvisionedThroughput.)
    (.setReadCapacityUnits (long read-units))
    (.setWriteCapacityUnits (long write-units))))

(defn create-table
  "Create a table in DynamoDB with the given map of properties."
  [cred {:keys [name hash-key range-key throughput]}]
  (.createTable
   (db-client cred)
   (doto (CreateTableRequest.)
     (.setTableName (str name))
     (.setKeySchema (key-schema hash-key range-key))
     (.setProvisionedThroughput
      (provisioned-throughput throughput)))))

(defn update-table
  "Update a table in DynamoDB with the given name."
  [cred {:keys [name throughput]}]
  (.updateTable
   (db-client cred)
   (doto (UpdateTableRequest.)
     (.setTableName (str name))
     (.setProvisionedThroughput
      (provisioned-throughput throughput)))))

(extend-protocol AsMap
  KeySchemaElement
  (as-map [element]
    {:name (.getAttributeName element)
     :type (-> (.getAttributeType element)
               (str/lower-case)
               (keyword))})
  KeySchema
  (as-map [schema]
    (merge
     (if-let [e (.getHashKeyElement schema)]  {:hash-key  (as-map e)} {})
     (if-let [e (.getRangeKeyElement schema)] {:range-key (as-map e)} {})))
  ProvisionedThroughputDescription
  (as-map [throughput]
    {:read  (.getReadCapacityUnits throughput)
     :write (.getWriteCapacityUnits throughput)
     :last-decrease (.getLastDecreaseDateTime throughput)
     :last-increase (.getLastIncreaseDateTime throughput)})
  DescribeTableResult
  (as-map [result]
    (let [table (.getTable result)]
      {:name             (.getTableName table)
       :creation-date    (.getCreationDateTime table)
       :item-count       (.getItemCount table)
       :table-size-bytes (.getTableSizeBytes table)
       :key-schema       (as-map (.getKeySchema table))
       :throughput       (as-map (.getProvisionedThroughput table))
       :status           (-> (.getTableStatus table)
                             (str/lower-case)
                             (keyword))})))

(defn describe-table
  "Returns a map describing the table in DynamoDB with the given name, or nil
  if the table does not exist."
  [cred name]
  (try
    (as-map
     (.describeTable
      (db-client cred)
      (doto (DescribeTableRequest.)
        (.setTableName name))))
    (catch ResourceNotFoundException _
      nil)))

(defn ensure-table
  "Creates the table if it does not already exist, updates the provisioned
  throughput if it does."
  [cred {:keys [name hash-key range-key throughput] :as properties}]
  (if-let [table (describe-table cred name)]
    (if (not= throughput (-> table :throughput (select-keys [:read :write])))
      (update-table cred properties))
    (create-table cred properties)))

(defn delete-table
  "Delete a table in DynamoDB with the given name."
  [cred name]
  (.deleteTable
   (db-client cred)
   (DeleteTableRequest. name)))

(defn list-tables
  "Return a list of tables in DynamoDB."
  [cred]
  (-> (db-client cred)
      .listTables
      .getTableNames
      seq))

(defn- normalize-operator [operator]
  "Maps Clojure operators to DynamoDB operators"
  (let [operator-map {:> "GT" :>= "GE" :< "LT" :<= "LE" := "EQ"}
        op (->> operator name str/upper-case)]
    (operator-map (keyword op) op)))

(defn- to-attr-value
  "Convert a value into an AttributeValue object."
  [value]
  (cond
   (string? value)
   (doto (AttributeValue.) (.setS value))
   (number? value)
   (doto (AttributeValue.) (.setN (str value)))
   (coll? value)
   (cond
    (string? (first value))
    (doto (AttributeValue.) (.setSS value))
    (number? (first value))
    (doto (AttributeValue.) (.setNS (map str value))))))

(defn- to-attr-value-update
  "Convert an action and a value into an AttributeValueUpdate object."
  [value-clause]
  (let [[operator value] value-clause]
    (AttributeValueUpdate. (to-attr-value value) (normalize-operator operator))))

(defn- get-value
  "Get the value of an AttributeValue object."
  [attr-value]
  (or (.getS attr-value)
      (.getN attr-value)
      (if-let [v (.getNS attr-value)] (into #{} v))
      (if-let [v (.getSS attr-value)] (into #{} v))))

(defn- to-expected-value
  "Convert a value to an ExpcetedValue object. Handles ::exists
  and ::not-exists specially."
  [value]
  (cond (= value ::exists) (ExpectedAttributeValue. true)
        (= value ::not-exists) (ExpectedAttributeValue. false)
        :else (if-let [av (to-attr-value value)] (ExpectedAttributeValue. av))))

(defn- to-expected-values
  [values]
  (and (seq values)
       (fmap to-expected-value values)))

(defn- item-map
  "Turn a item in DynamoDB into a Clojure map."
  [item]
  (if item
    (fmap get-value (into {} item))))

(extend-protocol AsMap
  GetItemResult
  (as-map [result]
    (with-meta
      (item-map (.getItem result))
      {:consumed-capacity-units (.getConsumedCapacityUnits result)}))

  PutItemResult
  (as-map [result]
    (with-meta
      (item-map (or (.getAttributes result) {}))
      {:consumed-capacity-units (.getConsumedCapacityUnits result)}))

  DeleteItemResult
  (as-map [result]
    (with-meta
      (item-map (or (.getAttributes result) {}))
      {:consumed-capacity-units (.getConsumedCapacityUnits result)})))

(defn put-item
  "Add an item (a Clojure map) to a DynamoDB table."
  ;; TODO Add documnetation about "expected" and "return-values"
  ;; TODO Consider using keywords for the "return-values"
  [cred table item & {:keys [expected return-values]}]
  (as-map
   (.putItem
    (db-client cred)
    (doto (PutItemRequest.)
      (.setTableName table)
      (.setItem (fmap to-attr-value item))
      (.setExpected (to-expected-values expected))
      (.setReturnValues return-values)))))

(defn- to-put-request [item]
  "Transforms an item (a Clojure map) into a PutRequest"
  (doto (PutRequest.)
    (.setItem (fmap to-attr-value item))))

(defn- to-write-request [item]
  "Transforms an item (a Clojure map) into a WriteRequest"
  (doto (WriteRequest.)
    (.setPutRequest (to-put-request item))))

(defn- put-batch [cred table write-requests]
  "Add up to 25 write requests to a DynamoDB table as a batch."
  (.batchWriteItem
   (db-client cred)
   (doto (BatchWriteItemRequest.)
     (.setRequestItems {table write-requests}))))

(defn batch-write [cred table items]
  "Add up to 25 items (Clojure maps) to a DynamoDB table as a batch."
  (let [write-requests (map to-write-request items)]
    (put-batch cred table write-requests)))

(defn multiple-batch-write
  "Batch writes the items (Clojure maps) in groups of 25 to a DynamoDB table. Retries failing groups once."
  [cred table item-coll]
  (let [get-unprocessed (fn [res] (flatten (map vec (vals (.getUnprocessedItems res)))))
        partitions (partition-all 25 item-coll)
        success (atom true)]
    (doseq [items partitions]
      (if @success
        (let [response (batch-write cred table items)
              unprocessed (get-unprocessed response)]
          (if (seq unprocessed)
            (let [retry-response (put-batch cred table unprocessed)
                  retry-unprocessed (get-unprocessed retry-response)]
              (if (seq retry-unprocessed)
                (swap! success (constantly false))))))))
    @success))

(defn- item-key
  "Create a Key object from a value."
  ([[hash-key range-key :as key]]
     (cond
         (nil? key) nil
         (vector? key) (item-key hash-key range-key)
         :else (item-key key nil)))
  ([hash-key range-key]
     (Key. (to-attr-value hash-key)
           (to-attr-value range-key))))

(defn- decode-key [k]
  (let [hash (.getHashKeyElement k)
        range (.getRangeKeyElement k)]
    (if range
      [(get-value hash) (get-value range)]
      (get-value hash))))

(defn update-item
  "Update an item (a Clojure map) in a DynamoDB table."
  ;; TODO Add documnetation about "update-map"
  ;; TODO Add documnetation about "expected" and "return-values"
  ;; TODO Consider using keywords for the "return-values"
  [cred table [hash-key range-key :as key] update-map & {:keys [expected return-values]}]
  (let [attribute-update-map (fmap to-attr-value-update (apply hash-map update-map))]
    (.updateItem
     (db-client cred)
     (doto (UpdateItemRequest.)
       (.setTableName table)
       (.setKey (item-key key))
       (.setAttributeUpdates attribute-update-map)
       (.setExpected (to-expected-values expected))
       (.setReturnValues return-values)))))

(defn get-item
  "Retrieve an item from a DynamoDB table by its key.
  The key can be given in the forms:
   * \"hash\"
   * [\"hash\"]
   * [\"hash\" \"range\"]"
  ;; TODO Add documnetation about "consistent-read" and "attributes-to-get"
  [cred table [hash-key range-key :as key] & {:keys [consistent-read attributes-to-get] :or {consistent-read false}}]
  (as-map
   (.getItem
    (db-client cred)
    (doto (GetItemRequest.)
      (.setTableName table)
      (.setKey (item-key key))
      (.setConsistentRead consistent-read)
      (.setAttributesToGet attributes-to-get)))))

(defn delete-item
  "Delete an item from a DynamoDB table specified by its key(s), if
  the coditions are met."
  ;; TODO Add documnetation about "expected" and "return-values"
  ;; TODO Consider using keywords for the "return-values"
  [cred table [hash-key range-key :as key] & {:keys [expected return-values]}]
  (as-map
   (.deleteItem
    (db-client cred)
    (doto (DeleteItemRequest. table (item-key key))
      (.setExpected (to-expected-values expected))
      (.setReturnValues return-values)))))

(defn- set-range-condition
  "Add the range key condition to a QueryRequest object"
  [query-request operator & [range-key range-end]]
  (let [attribute-list (map (fn [arg] (to-attr-value arg)) (remove nil? [range-key range-end]))]
    (.setRangeKeyCondition query-request
                           (doto (Condition.)
                             (.withComparisonOperator operator)
                             (.withAttributeValueList attribute-list)))))

(defn- query-request
  "Create a QueryRequest object."
  [table hash-key range-clause {:keys [order limit count consistent exclusive-start-key]}]
  (let [qr (QueryRequest. table (to-attr-value hash-key))
        [operator range-key range-end] range-clause]
    (when operator
      (set-range-condition qr (normalize-operator operator) range-key range-end))
    (when order
      (.setScanIndexForward qr (not= order :desc)))
    (when limit
      (.setLimit qr (int limit)))
    (when count
      (.setCount qr count))
    (when consistent
      (.setConsistentRead qr consistent))
    (when exclusive-start-key
      (.setExclusiveStartKey qr (item-key exclusive-start-key)))
    qr))

(defn query
  "Return the items in a DynamoDB table matching the supplied hash key.
  Can specify a range clause if the table has a range-key ie. `(>= 234)
  Takes the following options:
    :order - may be :asc or :desc (defaults to :asc)
    :limit - should be a positive integer
    :count - return a count if logical true
    :consistent - return a consistent read if logical true
    :exclusive-start-key - primary key of the item from which to continue an earlier query"
  [cred table hash-key range-clause & {:keys [order limit count consistent exclusive-start-key] :as options}]
  (let [query-result (.query
                      (db-client cred)
                      (query-request table hash-key range-clause options))]
    (with-meta
      (map item-map (or (.getItems query-result) {}))
      (merge {:count (.getCount query-result)
              :consumed-capacity-units (.getConsumedCapacityUnits query-result)}
             (if-let [k (.getLastEvaluatedKey query-result)]
               {:last-evaluated-key (decode-key k)})))))

(defn scan
  "Return the items in a DynamoDB table."
  [cred table & {:keys [attributes-to-get count exclusive-start-key limit scan-filter]}]
  (map item-map
       (.getItems
        (.scan
         (db-client cred)
         (doto (ScanRequest. table)
           (.setAttributesToGet attributes-to-get)
           (.setCount count)
           (.setExclusiveStartKey (item-key exclusive-start-key))
           (.setLimit limit)
           )))))
