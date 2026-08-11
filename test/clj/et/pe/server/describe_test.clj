(ns et.pe.server.describe-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [et.pe.server :as server]
            [et.pe.server.handlers]))

(def ^:private api-routes
  #{["GET"  "/api/describe"]
    ["GET"  "/api/personas"]
    ["POST" "/api/personas"]
    ["PUT"  "/api/personas/:name"]
    ["DELETE" "/api/personas/:name"]
    ["GET"  "/api/me"]
    ["POST" "/api/machine-users"]
    ["POST" "/api/machine-users/:name/token"]
    ["PUT"  "/api/machine-users/:name"]
    ["DELETE" "/api/machine-users/:name"]
    ["GET"  "/api/accounts"]
    ["POST" "/api/accounts"]
    ["GET"  "/api/generate-id"]
    ["GET"  "/api/auth/required"]
    ["POST" "/api/auth/login"]
    ["GET"  "/api/personas/:name/identities"]
    ["POST" "/api/personas/:name/identities"]
    ["GET"  "/api/personas/:name/identities/recent"]
    ["GET"  "/api/personas/:name/identities/search"]
    ["GET"  "/api/personas/:name/identities/:id"]
    ["PUT"  "/api/personas/:name/identities/:id"]
    ["GET"  "/api/personas/:name/identities/:id/at"]
    ["GET"  "/api/personas/:name/identities/:id/history"]
    ["GET"  "/api/personas/:name/identities/:id/provenance"]
    ["GET"  "/api/personas/:name/identities/:id/relations"]})

(deftest describe-lists-every-route
  (let [{:keys [status body]} (server/describe-handler {})]
    (is (= 200 status))
    (is (vector? body))
    (testing "exactly the routed endpoints, no more and no fewer"
      (is (= api-routes (set (map (juxt :method :path) body)))))

    (testing "every entry has the house shape {:name :ns :method :path :arglists :doc}"
      (doseq [entry body]
        (is (= #{:name :ns :method :path :arglists :doc} (set (keys entry))))
        (is (string? (:name entry)))
        (is (string? (:ns entry)))
        (is (string? (:arglists entry)))
        (is (not (str/blank? (:doc entry))) (str (:name entry) " must carry a docstring"))
        (is (re-find #"^(GET|POST|PUT|DELETE|PATCH)\s+/api/" (:doc entry))
            (str (:name entry) " must open its docstring with 'VERB /api/...'"))))

    (testing "entries are sorted by path then method"
      (let [keys-seen (mapv (juxt :path :method) body)]
        (is (= keys-seen (sort keys-seen)))))

    (testing "every *-handler in et.pe.server.handlers is documented"
      (let [described (set (map (juxt :ns :name) body))]
        (doseq [[sym _] (ns-publics (find-ns 'et.pe.server.handlers))
                :when (str/ends-with? (name sym) "-handler")]
          (is (contains? described ["et.pe.server.handlers" (str sym)])
              (str "et.pe.server.handlers/" sym
                   " is a *-handler but missing from describe")))))))
