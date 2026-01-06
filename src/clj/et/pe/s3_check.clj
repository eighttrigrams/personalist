(ns et.pe.s3-check
  (:require [taoensso.telemere :as tel])
  (:import [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.core.sync RequestBody]
           [software.amazon.awssdk.services.s3.model PutObjectRequest GetObjectRequest]
           [software.amazon.awssdk.regions Region]))

(defn s3-health-check
  "Write a test file to S3 and read it back to verify connectivity"
  [bucket prefix]
  (try
    (tel/log! :info ["Starting S3 health check - bucket:" bucket "prefix:" prefix])

    (let [client (-> (S3Client/builder)
                     (.region (Region/of (or (System/getenv "AWS_REGION") "eu-north-1")))
                     (.build))
          test-key (str prefix "health-check.txt")
          test-content "S3 Health Check"
          timestamp (str (System/currentTimeMillis))]

      ;; Write test file
      (tel/log! :info ["Writing test file to S3:" test-key])
      (.putObject client
                  (-> (PutObjectRequest/builder)
                      (.bucket bucket)
                      (.key test-key)
                      (.build))
                  (RequestBody/fromString (str test-content " - " timestamp)))

      (tel/log! :info "Successfully wrote test file to S3")

      ;; Read test file back
      (tel/log! :info "Reading test file back from S3")
      (let [response (.getObject client
                                 (-> (GetObjectRequest/builder)
                                     (.bucket bucket)
                                     (.key test-key)
                                     (.build)))
            content (slurp response)]

        (tel/log! :info ["Successfully read test file from S3. Content:" content])

        (if (.startsWith content test-content)
          (do
            (tel/log! :info "✓ S3 health check PASSED")
            {:success true :message "S3 connectivity verified"})
          (do
            (tel/log! :error ["S3 health check FAILED - content mismatch. Expected:" test-content "Got:" content])
            {:success false :message "Content mismatch"}))))

    (catch Exception e
      (tel/log! :error ["S3 health check FAILED with exception:" (.getMessage e)])
      (.printStackTrace e)
      {:success false :message (.getMessage e)})))
