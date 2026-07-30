(ns et.pe.server.describe-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [et.pe.server :as server]
            [et.pe.server.handlers]))

(defn- describe []
  (server/describe-handler {}))

(deftest describe-endpoint-returns-the-api-surface
  (let [{:keys [status body]} (describe)]
    (testing "returns 200 with a non-empty flat vector"
      (is (= 200 status))
      (is (vector? body))
      (is (pos? (count body))))

    (testing "every entry has the house shape {:name :ns :method :path :arglists :doc}"
      (doseq [entry body]
        (is (= #{:name :ns :method :path :arglists :doc} (set (keys entry))))
        (is (string? (:name entry)))
        (is (string? (:ns entry)))
        (is (contains? #{"GET" "POST" "PUT" "DELETE" "PATCH"} (:method entry)))
        (is (str/starts-with? (:path entry) "/api/"))
        (is (string? (:arglists entry)))
        (is (seq (:doc entry)))))

    (testing "entries are sorted by path then method"
      (let [keys-seen (mapv (juxt :path :method) body)]
        (is (= keys-seen (sort keys-seen)))))

    (testing "describe advertises itself"
      (is (contains? (set (map (juxt :ns :name) body))
                     ["et.pe.server" "describe-handler"])))

    (testing "non-route publics stay out of the listing"
      (let [names (set (map :name body))]
        (doseq [n ["set-conn!" "set-config!" "ensure-conn" "verify-token-check"
                   "wrap-rate-limit" "build-handler" "prod-mode?" "app"]]
          (is (not (contains? names n)) (str n " should not be advertised as a route")))))

    (testing "every *-handler in et.pe.server.handlers is documented"
      (let [described (set (map (juxt :ns :name) body))]
        (doseq [[sym _] (ns-publics (find-ns 'et.pe.server.handlers))
                :when (str/ends-with? (name sym) "-handler")]
          (is (contains? described ["et.pe.server.handlers" (str sym)])
              (str "et.pe.server.handlers/" sym
                   " is a *-handler but missing from describe")))))

    (testing "every route in api-routes is described"
      (let [described (set (map (juxt :method :path) body))]
        (doseq [pair [["GET" "/api/describe"]
                      ["GET" "/api/personas"]
                      ["POST" "/api/personas"]
                      ["PUT" "/api/personas/:name"]
                      ["GET" "/api/generate-id"]
                      ["GET" "/api/auth/required"]
                      ["POST" "/api/auth/login"]
                      ["GET" "/api/personas/:name/identities"]
                      ["GET" "/api/personas/:name/identities/recent"]
                      ["GET" "/api/personas/:name/identities/search"]
                      ["POST" "/api/personas/:name/identities"]
                      ["PUT" "/api/personas/:name/identities/:id"]
                      ["GET" "/api/personas/:name/identities/:id/at"]
                      ["GET" "/api/personas/:name/identities/:id/history"]
                      ["GET" "/api/personas/:name/identities/:id/relations"]
                      ["GET" "/api/personas/:name/identities/:id"]]]
          (is (contains? described pair) (str (str/join " " pair) " missing from describe")))))

    (testing "the listing covers the routes and nothing more"
      (is (= 16 (count body))))))
