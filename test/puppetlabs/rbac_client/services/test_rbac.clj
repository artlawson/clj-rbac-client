(ns puppetlabs.rbac-client.services.test-rbac
  (:require [clojure.test :refer [are deftest is testing]]
            [puppetlabs.http.client.sync :refer [create-client]]
            [puppetlabs.kitchensink.json :as json]
            [puppetlabs.rbac-client.protocols.rbac :as rbac]
            [puppetlabs.rbac-client.services.rbac :refer [remote-rbac-consumer-service api-url->status-url perm-str->map]]
            [puppetlabs.rbac-client.testutils.config :as cfg]
            [puppetlabs.rbac-client.testutils.http :as http]
            [puppetlabs.trapperkeeper.logging :refer [reset-logging]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [slingshot.test]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.webserver :refer [with-test-webserver-and-config]])
  (:import [java.util UUID]))

(def ^:private rand-subject {:login "rando@whoknows.net", :id (UUID/randomUUID)})

(def ^:private configs
  (let [server-cfg (cfg/jetty-ssl-config)]
    {:server server-cfg
     :client (cfg/rbac-client-config server-cfg)}))

(defn- wrap-test-handler-middleware
  [handler]
  (http/wrap-test-handler-middleware handler (:client configs)))

(deftest test-perm-str->map
  (testing "returns the correct value for a * permission"
    (is (= {:object_type "environment"
            :action "deploy_code"
            :instance "production"}
           (perm-str->map "environment:deploy_code:production"))))

  (testing "returns the correct value for a simple permission"
    (is (= {:object_type "console_page"
            :action "view"
            :instance "*"}
           (perm-str->map "console_page:view:*"))))

  (testing "returns the correct value for an instance containing colons"
    (is (= {:object_type "tasks"
            :action "run"
            :instance "package::install"}
         (perm-str->map "tasks:run:package::install")))))

(deftest test-is-permitted?
  (testing "is-permitted? returns the first result from RBAC's API"
    (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
      (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)]
        (doseq [result [[true] [false]]]
          (let [handler (wrap-test-handler-middleware
                          (constantly (http/json-200-resp result)))]
            (with-test-webserver-and-config handler _ (:server configs)
              (is (= (first result)
                     (rbac/is-permitted? consumer-svc rand-subject "users:disable:1"))))))))))

(deftest test-are-permitted?
  (testing "are-permitted? passes through the result from RBAC's API"
    (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
      (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)]
        (dotimes [_ 10]
          (let [result (vec (repeatedly (rand-int 30) #(< (rand) 0.5)))
                handler (wrap-test-handler-middleware
                          (constantly (http/json-200-resp result)))]
            (with-test-webserver-and-config handler _ (:server configs)
              (is (= result
                     (rbac/are-permitted? consumer-svc rand-subject ["users:disable:1"]))))))))))

(deftest test-cert-whitelisted?
  (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
    (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)]
      (testing "returns the result from RBAC using v2 endpoint"
        (doseq [result [true false]]
          (let [handler (wrap-test-handler-middleware
                          (fn [req]
                           (when (= "/rbac-api/v2/certs/foobar" (get req :uri))
                             (http/json-200-resp {:cn "foobar", :allowlisted result, :subject rand-subject}))))]
            (with-test-webserver-and-config handler _ (:server configs)
              (is (= result (rbac/cert-whitelisted? consumer-svc "foobar")))))))
      (testing "tries v2, falls back to v1, and v1 afterward"
        (let [subject rand-subject
              v2-count (atom 0)
              v1-count (atom 0)
              handler (wrap-test-handler-middleware
                        (fn [req]
                          (case (get req :uri)
                            "/rbac-api/v2/certs/foobar" (do
                                                          (swap! v2-count inc)
                                                          (http/json-resp 404 {}))
                            "/rbac-api/v1/certs/foobar" (do
                                                          (swap! v1-count inc)
                                                          (http/json-200-resp {:cn "foobar", :whitelisted true, :subject subject})))))]
          (with-test-webserver-and-config handler _ (:server configs)
            (is (= true (rbac/cert-whitelisted? consumer-svc "foobar")))
            (is (= 1 (deref v2-count)))
            (is (= 1 (deref v1-count)))
            (is (= true (rbac/cert-whitelisted? consumer-svc "foobar")))
            (is (= true (rbac/cert-whitelisted? consumer-svc "foobar")))
            (is (= true (rbac/cert-whitelisted? consumer-svc "foobar")))
            (is (= 1 (deref v2-count)))
            (is (= 4 (deref v1-count))))))
      (testing "returns false when no cert is supplied"
        (is (not (rbac/cert-whitelisted? consumer-svc nil)))))))

(deftest test-cert-allowed?
  (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
    (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)]
      (testing "returns the result from RBAC using v2 endpoint"
        (doseq [result [true false]]
          (let [handler (wrap-test-handler-middleware
                         (fn [req]
                          (when (= "/rbac-api/v2/certs/foobar" (get req :uri))
                            (http/json-200-resp {:cn "foobar", :allowlisted result, :subject rand-subject}))))]
            (with-test-webserver-and-config handler _ (:server configs)
                                            (is (= result (rbac/cert-allowed? consumer-svc "foobar")))))))
      (testing "tries v2, falls back to v1, and v1 afterward"
        (let [subject rand-subject
              v2-count (atom 0)
              v1-count (atom 0)
              handler (wrap-test-handler-middleware
                       (fn [req]
                         (case (get req :uri)
                           "/rbac-api/v2/certs/foobar" (do
                                                         (swap! v2-count inc)
                                                         (http/json-resp 404 {}))
                           "/rbac-api/v1/certs/foobar" (do
                                                         (swap! v1-count inc)
                                                         (http/json-200-resp {:cn "foobar", :whitelisted true, :subject subject})))))]
          (with-test-webserver-and-config handler _ (:server configs)
                                          (is (= true (rbac/cert-allowed? consumer-svc "foobar")))
                                          (is (= 1 (deref v2-count)))
                                          (is (= 1 (deref v1-count)))
                                          (is (= true (rbac/cert-allowed? consumer-svc "foobar")))
                                          (is (= true (rbac/cert-allowed? consumer-svc "foobar")))
                                          (is (= true (rbac/cert-allowed? consumer-svc "foobar")))
                                          (is (= 1 (deref v2-count)))
                                          (is (= 4 (deref v1-count))))))
      (testing "returns false when no cert is supplied"
        (is (not (rbac/cert-allowed? consumer-svc nil)))))))

(deftest test-cert->subject
 (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
    (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)]
      (testing "uses the v2 endpoint"
        (doseq [subject [rand-subject nil]]
          (let [handler (wrap-test-handler-middleware
                         (fn [req]
                           (when (= "/rbac-api/v2/certs/foobar" (get req :uri))
                            (http/json-200-resp {:cn "foobar", :allowlisted (some? subject), :subject subject}))))]
            (with-test-webserver-and-config handler _ (:server configs)
              (is (= subject (rbac/cert->subject consumer-svc "foobar")))))))
      (testing "tries v2, falls back to v1, and v1 afterward"
        (let [subject rand-subject
              v2-count (atom 0)
              v1-count (atom 0)
              handler (wrap-test-handler-middleware
                        (fn [req]
                          (case (get req :uri)
                            "/rbac-api/v2/certs/foobar" (do
                                                          (swap! v2-count inc)
                                                          (http/json-resp 404 {}))
                            "/rbac-api/v1/certs/foobar" (do
                                                          (swap! v1-count inc)
                                                          (http/json-200-resp {:cn "foobar", :whitelisted (some? subject), :subject subject})))))]
          (with-test-webserver-and-config handler _ (:server configs)
            (is (= subject (rbac/cert->subject consumer-svc "foobar")))
            (is (= 1 (deref v2-count)))
            (is (= 1 (deref v1-count)))
            (is (= subject (rbac/cert->subject consumer-svc "foobar")))
            (is (= subject (rbac/cert->subject consumer-svc "foobar")))
            (is (= subject (rbac/cert->subject consumer-svc "foobar")))
            (is (= 1 (deref v2-count)))
            (is (= 4 (deref v1-count))))))

      (testing "returns nil when no cert is supplied"
        (is (nil? (rbac/cert->subject consumer-svc nil)))))))

(deftest test-valid-token->subject
  (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
    (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)
          !last-request (atom nil)
          handler (wrap-test-handler-middleware
                    (fn [req]
                      (reset! !last-request req)
                      (http/json-200-resp rand-subject)))]

      (with-test-webserver-and-config handler _ (:server configs)
        (testing "valid-token->subject"
          (let [returned-subject (rbac/valid-token->subject consumer-svc "token")
                {:keys [update_last_activity?]} (-> @!last-request
                                                 :body
                                                 (json/parse-string true))]
            (testing "returns the expected subject"
              (is (= rand-subject returned-subject)))
            (testing "updates the token's activity"
              (is (true? update_last_activity?))))

          (testing "doesn't update activity when the token is suffixed with '|no_keepalive'"
            (let [_ (rbac/valid-token->subject consumer-svc "token|no_keepalive")
                  {:keys [update_last_activity?]} (-> @!last-request
                                                      :body
                                                      (json/parse-string true))]
              (is (false? update_last_activity?)))))))))

(deftest test-list-permitted
  (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
    (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)]
        (let [handler (wrap-test-handler-middleware
                       (fn [req]
                         (cond
                           (not (= "token" (get-in req [:headers "x-authentication"])))
                           (http/json-resp 400 {:kind "mismatched token"})

                           (not (= "/rbac-api/v1/permitted/numbers/count" (get req :uri)))
                           (http/json-resp 400 {:kind "incorrect url structure"})

                           :default
                           (http/json-200-resp ["one" "two" "three"]))))]
          (with-test-webserver-and-config handler _ (assoc (:server configs) :client-auth "want")
              (is (= ["one" "two" "three"] (rbac/list-permitted consumer-svc "token" "numbers" "count"))))))))

(deftest test-list-permitted-for
  (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
    (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)]
      (let [mock-subj {:id "12345"
                       :login "login-string"}
            handler (wrap-test-handler-middleware
                     (fn [req]
                       (cond
                         (not (= "/rbac-api/v1/permitted/object-type/action/12345" (get req :uri)))
                         (http/json-resp 400 {:kind "incorrect url structure"})

                         :default
                         (http/json-200-resp ["four" "five" "six"]))))]
        (with-test-webserver-and-config handler _ (assoc (:server configs) :client-auth "want")
          (is (= ["four" "five" "six"] (rbac/list-permitted-for consumer-svc mock-subj "object-type" "action"))))))))

(deftest test-subject
  (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
    (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)]
      (let [mock-subj {:id #uuid "2aa80edb-7f6a-4b94-b6ad-ce9a4cb453b2"
                       :login "login-string"}
            handler (wrap-test-handler-middleware
                     (fn [req]
                       (cond
                         (not (= "/rbac-api/v1/users/2aa80edb-7f6a-4b94-b6ad-ce9a4cb453b2" (get req :uri)))
                         (http/json-resp 400 {:kind "incorrect url structure"})

                         :default
                         (http/json-200-resp mock-subj))))]
        (with-test-webserver-and-config handler _ (assoc (:server configs) :client-auth "want")
          (is (= mock-subj (rbac/subject consumer-svc "2aa80edb-7f6a-4b94-b6ad-ce9a4cb453b2"))))))))

(deftest test-status-url
  (are [service-url rbac-api-url] (= service-url (api-url->status-url rbac-api-url))
    "https://foo.com:4444/status/v1/services"
    "https://foo.com:4444/rbac/rbac-api"

    "http://foo.com:4444/status/v1/services"
    "http://foo.com:4444/rbac/rbac-api"

    "https://foo.com/status/v1/services"
    "https://foo.com/rbac/rbac-api"

    "http://foo.com/status/v1/services"
    "http://foo.com/rbac/rbac-api"))

(deftest test-unconfigured
  (reset-logging)
  (with-test-logging
    (is (thrown+-with-msg? [:kind :puppetlabs.rbac-client/invalid-configuration]
                           #"'rbac-consumer' not configured with an 'api-url'"
          (with-app-with-config tk-app [remote-rbac-consumer-service]
            (assoc-in (:client configs) [:rbac-consumer :api-url] nil)
            nil)))))

(deftest test-status-check
  (with-app-with-config tk-app [remote-rbac-consumer-service] (:client configs)
    (let [consumer-svc (tk-app/get-service tk-app :RbacConsumerService)
          status-results {:activity-service
                          {:service_version "0.5.3",
                           :service_status_version 1,
                           :detail_level "info",
                           :state "running",
                           :status {:db_up true}}

                          :rbac-service
                          {:service_version "1.2.12",
                           :service_status_version 1,
                           :detail_level "info",
                           :state "running",
                           :status {:db_up true,
                                    :activity_up true}}}

          failed-response (http/malformed-json-400-resp "No 'level' parameter found in request")
          handler (wrap-test-handler-middleware
                   (fn [req]
                     (if (= "critical" (get-in req [:params "level"]))
                       (http/json-200-resp status-results)
                       failed-response)))
          error-handler (wrap-test-handler-middleware
                         (fn [req]
                           (if (= "critical" (get-in req [:params "level"]))
                             (http/json-200-resp
                              (-> status-results
                                  (assoc-in [:rbac-service :state] "error")
                                  (assoc-in [:rbac-service :status :db_up] false)))
                             failed-response)))]

      (with-test-webserver-and-config handler _ (:server configs)
        (is (= {:service_version "1.2.12",
                :service_status_version 1,
                :detail_level "info",
                :state :running,
                :status {:db_up true,
                         :activity_up true}}
               (rbac/status consumer-svc "critical"))))

      (with-test-webserver-and-config error-handler _ (:server configs)
        (is (= {:service_version "1.2.12",
                :service_status_version 1,
                :detail_level "info",
                :state :error,
                :status {:db_up false,
                         :activity_up true}}
               (rbac/status consumer-svc "critical")))))))
