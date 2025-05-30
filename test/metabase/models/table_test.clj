(ns metabase.models.table-test
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   [metabase.models.field-values :as field-values]
   [metabase.models.serialization :as serdes]
   [metabase.models.table :as table]
   [metabase.permissions.models.data-permissions :as data-perms]
   [metabase.permissions.models.permissions-group :as perms-group]
   [metabase.sync.core :as sync]
   [metabase.test :as mt]
   [metabase.test.data.one-off-dbs :as one-off-dbs]
   [metabase.test.fixtures :as fixtures]
   [metabase.util :as u]
   [toucan2.core :as t2]))

(use-fixtures :once (fixtures/initialize :db :test-users))

(deftest valid-field-order?-test
  (testing "A valid field ordering is a set IDs  of all active fields in a given table"
    (is (#'table/valid-field-order? (mt/id :venues)
                                    [(mt/id :venues :name)
                                     (mt/id :venues :category_id)
                                     (mt/id :venues :latitude)
                                     (mt/id :venues :longitude)
                                     (mt/id :venues :price)
                                     (mt/id :venues :id)])))
  (testing "Field ordering is invalid if some fields are missing"
    (is (false? (#'table/valid-field-order? (mt/id :venues)
                                            [(mt/id :venues :category_id)
                                             (mt/id :venues :latitude)
                                             (mt/id :venues :longitude)
                                             (mt/id :venues :price)
                                             (mt/id :venues :id)]))))
  (testing "Field ordering is invalid if some fields are from a differnt table"
    (is (false? (#'table/valid-field-order? (mt/id :venues)
                                            [(mt/id :venues :name)
                                             (mt/id :venues :category_id)
                                             (mt/id :venues :latitude)
                                             (mt/id :venues :longitude)
                                             (mt/id :venues :price)
                                             (mt/id :checkins :id)]))))
  (testing "Only active fields should be considerd when checking field order"
    (one-off-dbs/with-blank-db
      (doseq [statement [;; H2 needs that 'guest' user for QP purposes. Set that up
                         "CREATE USER IF NOT EXISTS GUEST PASSWORD 'guest';"
                         ;; Keep DB open until we say otherwise :)
                         "SET DB_CLOSE_DELAY -1;"
                         ;; create table & load data
                         "DROP TABLE IF EXISTS \"BIRDS\";"
                         "CREATE TABLE \"BIRDS\" (\"SPECIES\" VARCHAR PRIMARY KEY, \"EXAMPLE_NAME\" VARCHAR);"
                         "GRANT ALL ON \"BIRDS\" TO GUEST;"
                         (str "INSERT INTO \"BIRDS\" (\"SPECIES\", \"EXAMPLE_NAME\") VALUES "
                              "('House Finch', 'Marshawn Finch'),  "
                              "('California Gull', 'Steven Seagull'), "
                              "('Chicken', 'Colin Fowl');")]]
        (jdbc/execute! one-off-dbs/*conn* [statement]))
      (sync/sync-database! (mt/db))
      (is (#'table/valid-field-order? (mt/id :birds)
                                      [(mt/id :birds :species)
                                       (mt/id :birds :example_name)]))
      (jdbc/execute! one-off-dbs/*conn* ["ALTER TABLE \"BIRDS\" DROP COLUMN \"EXAMPLE_NAME\";"])
      (sync/sync-database! (mt/db))
      (is (#'table/valid-field-order? (mt/id :birds)
                                      [(mt/id :birds :species)])))))

(deftest slashes-in-schema-names-test
  (testing "Schema names should allow forward or back slashes (#8693, #12450)"
    (doseq [schema-name ["my\\schema"
                         "my\\\\schema"
                         "my/schema"
                         "my\\/schema"
                         "my\\\\/schema"]]
      (testing (format "Should be able to create/delete Table with schema name %s" (pr-str schema-name))
        (mt/with-temp [:model/Table {table-id :id} {:schema schema-name}]
          (is (= schema-name
                 (t2/select-one-fn :schema :model/Table :id table-id))))))))

(deftest identity-hash-test
  (testing "Table hashes are composed of the schema name, table name and the database's identity-hash"
    (mt/with-temp [:model/Database db    {:name "field-db" :engine :h2}
                   :model/Table    table {:schema "PUBLIC" :name "widget" :db_id (:id db)}]
      (let [db-hash (serdes/identity-hash db)]
        (is (= "0395fe49"
               (serdes/raw-hash ["PUBLIC" "widget" db-hash])
               (serdes/identity-hash table)))))))

(deftest set-new-table-permissions!-test
  (testing "New permissions are set appropriately for a new table, for all groups"
    (mt/with-full-data-perms-for-all-users!
      (mt/with-temp [:model/PermissionsGroup {group-id :id} {}
                     :model/Database         {db-id :id}    {}
                     :model/Table            {table-id-1 :id} {:db_id  db-id
                                                               :schema "PUBLIC"}
                     :model/Table            {table-id-2 :id} {:db_id  db-id
                                                               :schema "PUBLIC"}]
        ;; Perms for new tables are the same as the DB-level perms, if they exist
        (let [all-users-group-id (u/the-id (perms-group/all-users))]
          (is (partial=
               {all-users-group-id
                {db-id
                 {:perms/view-data             :unrestricted
                  :perms/create-queries        :query-builder-and-native
                  :perms/download-results      :one-million-rows
                  :perms/manage-table-metadata :no
                  :perms/manage-database       :no}}}
               (data-perms/data-permissions-graph :group-id all-users-group-id :db-id db-id))))

        ;; A new group starts with the same perms as All Users
        (is (partial=
             {group-id
              {db-id
               {:perms/view-data             :unrestricted
                :perms/create-queries        :query-builder-and-native
                :perms/download-results      :one-million-rows
                :perms/manage-table-metadata :no
                :perms/manage-database       :no}}}
             (data-perms/data-permissions-graph :group-id group-id :db-id db-id)))

        (testing "A new table has appropriate defaults, when perms are already set granularly for the DB"
          (data-perms/set-table-permission! group-id table-id-1 :perms/create-queries :no)
          (data-perms/set-table-permission! group-id table-id-1 :perms/download-results :no)
          (data-perms/set-table-permission! group-id table-id-1 :perms/manage-table-metadata :no)
          (data-perms/set-table-permission! group-id table-id-2 :perms/create-queries :query-builder)
          (data-perms/set-table-permission! group-id table-id-2 :perms/download-results :one-million-rows)
          (data-perms/set-table-permission! group-id table-id-2 :perms/manage-table-metadata :yes)
          (mt/with-temp [:model/Table {table-id-3 :id} {:db_id  db-id
                                                        :schema "PUBLIC"}]
            (is (partial=
                 {group-id
                  {db-id
                   {:perms/view-data             :unrestricted
                    :perms/create-queries        {"PUBLIC"
                                                  {table-id-1 :no
                                                   table-id-2 :query-builder
                                                   table-id-3 :no}}
                    :perms/download-results      {"PUBLIC"
                                                  {table-id-1 :no
                                                   table-id-2 :one-million-rows
                                                   table-id-3 :no}}
                    :perms/manage-table-metadata {"PUBLIC"
                                                  {table-id-1 :no
                                                   table-id-2 :yes
                                                   table-id-3 :no}}
                    :perms/manage-database       :no}}}
                 (data-perms/data-permissions-graph :group-id group-id :db-id db-id)))))))))

(deftest cleanup-permissions-after-delete-table-test
  (mt/with-temp
    [:model/Database {db-id :id}      {}
     :model/Table    {table-id-1 :id} {:db_id db-id}
     :model/Table    {}               {:db_id db-id}]
    (data-perms/set-table-permission! (perms-group/all-users) table-id-1 :perms/create-queries :query-builder-and-native)
    (data-perms/set-table-permission! (perms-group/all-users) table-id-1 :perms/view-data :unrestricted)
    (data-perms/set-table-permission! (perms-group/all-users) table-id-1 :perms/download-results :one-million-rows)
    (data-perms/set-table-permission! (perms-group/all-users) table-id-1 :perms/manage-table-metadata :yes)
    (is (true? (t2/exists? :model/DataPermissions :table_id table-id-1)))
    (t2/delete! :model/Table table-id-1)
    (testing "Table-level permissions are deleted when we delete the table"
      (is (false? (t2/exists? :model/DataPermissions :table_id table-id-1))))))

;; hydration tests
(deftest field-values-hydration-test
  ;; Manually activate Field values since they are not created during sync (#53387)
  (field-values/get-or-create-full-field-values! (t2/select-one :model/Field :id (mt/id :venues :price)))
  (field-values/get-or-create-full-field-values! (t2/select-one :model/Field :id (mt/id :venues :name)))

  (is (=? {(mt/id :venues :price) (mt/malli=? [:sequential {:min 1} :any])
           (mt/id :venues :name)  (mt/malli=? [:sequential {:min 1} :any])}
          (-> (t2/select-one :model/Table (mt/id :venues))
              (t2/hydrate :field_values)
              :field_values))))

(deftest pk-field-hydration-test
  (is (= (mt/id :venues :id)
         (-> (t2/select-one :model/Table (mt/id :venues))
             (t2/hydrate :pk_field)
             :pk_field))))

(deftest visible-tables-filter-clause-test
  (testing "visible-tables-filter-clause generates a HoneySQL clause that filters tables based on user permissions"
    (mt/with-temp [:model/Database {db-id :id} {}
                   :model/Table    {table-id-1 :id} {:db_id db-id :name "Table1"}
                   :model/Table    {table-id-2 :id} {:db_id db-id :name "Table2"}
                   :model/Table    {table-id-3 :id} {:db_id db-id :name "Table3"}]
      (data-perms/set-database-permission! (perms-group/all-users) db-id :perms/view-data :blocked)
      (data-perms/set-database-permission! (perms-group/all-users) db-id :perms/create-queries :no)

      (data-perms/set-table-permission! (perms-group/all-users) table-id-1 :perms/view-data :unrestricted)
      (data-perms/set-table-permission! (perms-group/all-users) table-id-1 :perms/create-queries :query-builder)

      (data-perms/set-table-permission! (perms-group/all-users) table-id-2 :perms/view-data :unrestricted)
      (data-perms/set-table-permission! (perms-group/all-users) table-id-2 :perms/create-queries :query-builder-and-native)

      (data-perms/set-table-permission! (perms-group/all-users) table-id-3 :perms/view-data :blocked)
      (data-perms/set-table-permission! (perms-group/all-users) table-id-3 :perms/create-queries :no)

      (let [user-id            (mt/user->id :rasta)
            fetch-visible-ids (fn [user-cfg table-id-field]
                                (let [filter-clause (table/visible-tables-filter-clause table-id-field user-cfg)]
                                  (t2/select-pks-set [:model/Table]
                                                     {:where
                                                      [:and
                                                       [:= :db_id db-id]
                                                       filter-clause]})))]

        (testing "Superuser should see all tables"
          (let [superuser-cfg {:user-id       user-id ; User ID doesn't matter for superuser
                               :is-superuser? true
                               :view-data-permission-level :unrestricted ; Levels don't matter for superuser
                               :create-queries-permission-level :query-builder}]
            (is (= #{table-id-1 table-id-2 table-id-3}
                   (fetch-visible-ids superuser-cfg :id)))))

        (testing "Non-superuser should only see tables where they have both view and query permissions (mimics mi/can-read?)"
          (let [rasta-cfg {:user-id       user-id
                           :is-superuser? false
                           :view-data-permission-level :unrestricted
                           :create-queries-permission-level :query-builder}]
            (is (= #{table-id-1 table-id-2}
                   (fetch-visible-ids rasta-cfg :id))
                "Clause should filter out tables without sufficient view/query permissions")

            (testing "using a sequence of fields for the table ID"
              (is (= #{table-id-1 table-id-2}
                     (fetch-visible-ids rasta-cfg [:id :metabase_table.id]))
                  "Clause should work with coalesce when a sequence of fields is provided"))

            (testing "using a qualified keyword"
              (is (= #{table-id-1 table-id-2}
                     (fetch-visible-ids rasta-cfg :metabase_table.id))
                  "Clause should work with qualified keyword"))))

        (testing "Non-superuser requiring only :view-data :unrestricted should see tables 1 and 2"
          (let [rasta-view-only-cfg {:user-id       user-id
                                     :is-superuser? false
                                     :view-data-permission-level :unrestricted
                                     :create-queries-permission-level :no}]
            (is (= #{table-id-1 table-id-2}
                   (fetch-visible-ids rasta-view-only-cfg :id))
                "Clause should respect the provided permission levels")))

        (testing "Non-superuser requiring :view-data :blocked should all tables since all values are more permissive "
          (let [rasta-blocked-cfg {:user-id       user-id
                                   :is-superuser? false
                                   :view-data-permission-level :blocked
                                   :create-queries-permission-level :no}]
            (is (= #{table-id-1 table-id-2 table-id-3}
                   (fetch-visible-ids rasta-blocked-cfg :id))
                "Clause should filter correctly when requiring :blocked level"))))))
  (testing "visible-tables-filter-clause generates a HoneySQL clause that filters tables based on user permissions when only db level perms are set"
    (mt/with-temp [:model/Database {db-id :id} {}
                   :model/Table    {table-id-1 :id} {:db_id db-id :name "Table1"}
                   :model/Table    {table-id-2 :id} {:db_id db-id :name "Table2"}
                   :model/Table    {table-id-3 :id} {:db_id db-id :name "Table3"}]

      (data-perms/set-database-permission! (perms-group/all-users) db-id :perms/view-data :unrestricted)
      (data-perms/set-database-permission! (perms-group/all-users) db-id :perms/create-queries :no)

      (let [user-id            (mt/user->id :rasta)
            fetch-visible-ids (fn [user-cfg table-id-field]
                                (let [filter-clause (table/visible-tables-filter-clause table-id-field user-cfg)]
                                  (t2/select-pks-set [:model/Table]
                                                     {:where
                                                      [:and
                                                       [:= :db_id db-id]
                                                       filter-clause]})))]

        (testing "Superuser should see all tables"
          (let [superuser-cfg {:user-id       user-id ; User ID doesn't matter for superuser
                               :is-superuser? true
                               :view-data-permission-level :unrestricted ; Levels don't matter for superuser
                               :create-queries-permission-level :query-builder}]
            (is (= #{table-id-1 table-id-2 table-id-3}
                   (fetch-visible-ids superuser-cfg :id)))))

        (testing "Non-superuser should only see tables where they have both view and query permissions (mimics mi/can-read?)"
          (let [rasta-cfg {:user-id       user-id
                           :is-superuser? false
                           :view-data-permission-level :unrestricted
                           :create-queries-permission-level :query-builder}]
            (is (nil?
                 (fetch-visible-ids rasta-cfg :id))
                "Clause should filter out tables without sufficient view/query permissions")

            (testing "using a sequence of fields for the table ID"
              (is (nil?
                   (fetch-visible-ids rasta-cfg [:id :metabase_table.id]))
                  "Clause should work with coalesce when a sequence of fields is provided"))

            (testing "using a qualified keyword"
              (is (nil?
                   (fetch-visible-ids rasta-cfg :metabase_table.id))
                  "Clause should work with qualified keyword"))))

        (testing "Non-superuser requiring only :view-data :unrestricted should see tables 1 and 2"
          (let [rasta-view-only-cfg {:user-id       user-id
                                     :is-superuser? false
                                     :view-data-permission-level :unrestricted
                                     :create-queries-permission-level :no}]
            (is (= #{table-id-1 table-id-2 table-id-3}
                   (fetch-visible-ids rasta-view-only-cfg :id))
                "Clause should respect the provided permission levels")))

        (testing "Non-superuser requiring :view-data :blocked should all tables since all values are more permissive "
          (let [rasta-blocked-cfg {:user-id       user-id
                                   :is-superuser? false
                                   :view-data-permission-level :blocked
                                   :create-queries-permission-level :no}]
            (is (= #{table-id-1 table-id-2 table-id-3}
                   (fetch-visible-ids rasta-blocked-cfg :id))
                "Clause should filter correctly when requiring :blocked level")))))))
