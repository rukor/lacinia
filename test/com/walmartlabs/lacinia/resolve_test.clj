(ns com.walmartlabs.lacinia.resolve-test
  "Tests to ensure that field resolvers are passed expected values."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.test-schema :refer [test-schema]]
    [com.walmartlabs.lacinia :as graphql :refer [execute]]
    [clojure.walk :refer [postwalk]]
    [com.walmartlabs.lacinia.schema :as schema])
  (:import (clojure.lang ExceptionInfo)))

(def resolve-contexts (atom []))

(def ^:dynamic *compiled-schema* nil)

(defn ^:private instrument-and-compile
  [schema]
  (->> schema
       (postwalk (fn [node]
                   (if-let [default-resolve (:resolve node)]
                     (assoc node :resolve
                            (fn [context args value]
                              (swap! resolve-contexts conj context)
                              (default-resolve context args value)))
                     node)))
       schema/compile))

;; Ensure that resolve-contexts is reset to empty after each test execution.

(use-fixtures
  :each
  (fn [f]
    (f)
    (swap! resolve-contexts empty)))

(use-fixtures
  :once
  (fn [f]
    (binding [*compiled-schema* (instrument-and-compile test-schema)]
      (f))))


(deftest passes-root-query-to-resolve
  (let [q "query {
             human(id: \"1000\") {
               name
             }
           }"
        query-result (execute *compiled-schema* q nil {::my-key ::my-value})
        [c1 :as contexts] @resolve-contexts]

    (is (= {:data {:human {:name "Luke Skywalker"}}}
           query-result))

    ;; Just to verify that user context is passed through.

    (is (= ::my-value (::my-key c1)))

    ;; Only the resolve for the [:query :human] in thise case, as :name is a simple
    ;; default resolve (added during compilation).

    (is (= 1 (count contexts)))

    (is (= :human (-> c1 ::graphql/selection :field)))

    (is (= :human (-> c1 ::graphql/selection :field-definition :type)))

    ;; This is pretty important: can we see what else will be queried?
    ;; We're focusing in these tests on sub-fields with the root query field.

    (is (= 1 (-> c1 ::graphql/selection :selections count)))

    (is (= {:field :name
            :alias :name
            :query-path [:human :name]}
           (-> c1 ::graphql/selection :selections first (select-keys [:field :alias :query-path]))))))

(deftest passes-nested-selections-to-resolve
  (let [q "query { human(id: \"1000\") { buddies: friends { name }}}"
        query-result (execute *compiled-schema* q nil nil)
        [c1 c2 :as contexts] @resolve-contexts]
    (is (= {:data
            {:human
             {:buddies
              [{:name "Han Solo"}
               {:name "Leia Organa"}
               {:name "C-3PO"}
               {:name "R2-D2"}]}}}
           query-result))

    ;; Two: the resolve for the human query, and the resolve for the
    ;; nested friends field.

    (is (= 2 (count contexts)))

    ;; This is important; the upper resolves get a preview of what's going on with the
    ;; lower resolves. In theory, a database oriented query could use this to build a richer
    ;; query at the top resolve that "seeds" data that will simply be extracted by lower resolves.

    (is (= (-> c1 :selection :selections first)
           (:selection c2)))

    (is (= {:field :friends
            :alias :buddies
            :query-path [:human :friends]}
           (-> c2 ::graphql/selection (select-keys [:field :alias :query-path]))))))

(deftest checks-that-bare-values-are-wrapped-as-a-tuple
  (let [return-value "What, me worry?"
        schema (schema/compile {:queries {:catchphrase {:type :String
                                                        :resolve (constantly return-value)}}})]
    (is (= {:data {:catchphrase return-value}}
           (graphql/execute schema "{catchphrase}" nil nil))) 1))
