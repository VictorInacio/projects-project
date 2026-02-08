(ns projects.schema-test
  "Tests for Malli schema validation."
  (:require [clojure.test :refer [deftest testing is]]
            [projects.schema :as schema]))

(deftest project-create-validation
  (testing "valid project with name only"
    (let [result (schema/validate schema/ProjectCreate {:name "My Project"})]
      (is (:ok result))
      (is (= "My Project" (:name (:ok result))))))

  (testing "valid project with name and status"
    (let [result (schema/validate schema/ProjectCreate
                                  {:name "My Project" :status "on-hold"})]
      (is (:ok result))
      (is (= "on-hold" (:status (:ok result))))))

  (testing "name is trimmed"
    (let [result (schema/validate schema/ProjectCreate {:name "  Trimmed  "})]
      (is (:ok result))
      (is (= "Trimmed" (:name (:ok result))))))

  (testing "rejects empty name"
    (let [result (schema/validate schema/ProjectCreate {:name ""})]
      (is (:error result))))

  (testing "rejects whitespace-only name"
    (let [result (schema/validate schema/ProjectCreate {:name "   "})]
      (is (:error result))))

  (testing "rejects name over 200 characters"
    (let [long-name (apply str (repeat 201 "a"))
          result (schema/validate schema/ProjectCreate {:name long-name})]
      (is (:error result))))

  (testing "accepts name at 200 characters"
    (let [max-name (apply str (repeat 200 "a"))
          result (schema/validate schema/ProjectCreate {:name max-name})]
      (is (:ok result))))

  (testing "rejects invalid status"
    (let [result (schema/validate schema/ProjectCreate
                                  {:name "Test" :status "invalid"})]
      (is (:error result))))

  (testing "rejects missing name"
    (let [result (schema/validate schema/ProjectCreate {})]
      (is (:error result))))

  (testing "strips extra keys"
    (let [result (schema/validate schema/ProjectCreate
                                  {:name "Test" :extra "ignored"})]
      (is (:ok result))
      (is (nil? (:extra (:ok result)))))))

(deftest pagination-params-validation
  (testing "accepts valid params"
    (let [result (schema/validate schema/PaginationParams
                                  {:limit 10 :offset 20 :sort "-name"})]
      (is (:ok result))))

  (testing "rejects limit over 100"
    (let [result (schema/validate schema/PaginationParams {:limit 101})]
      (is (:error result))))

  (testing "rejects negative offset"
    (let [result (schema/validate schema/PaginationParams {:offset -1})]
      (is (:error result))))

  (testing "rejects invalid sort field"
    (let [result (schema/validate schema/PaginationParams {:sort "invalid"})]
      (is (:error result))))

  (testing "accepts all valid sort options"
    (doseq [sort ["created_at" "-created_at" "name" "-name"]]
      (let [result (schema/validate schema/PaginationParams {:sort sort})]
        (is (:ok result) (str "Sort option '" sort "' should be valid"))))))