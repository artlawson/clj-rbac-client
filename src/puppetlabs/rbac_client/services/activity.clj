(ns puppetlabs.rbac-client.services.activity
  (:require
   [clojure.tools.logging :as log]
   [puppetlabs.i18n.core :as i18n]
   [puppetlabs.rbac-client.protocols.activity :refer [ActivityReportingService]]
   [puppetlabs.http.client.common :as http]
   [puppetlabs.http.client.sync :refer [create-client]]
   [puppetlabs.rbac-client.core :refer [json-api-caller]]
   [puppetlabs.trapperkeeper.core :refer [defservice]]
   [puppetlabs.trapperkeeper.services :refer [service-context]]))

(defn v1->v2
  ;; make any adjustments needed to make a (maybe) v1 payload into a v2 payload
  [event-bundle]
  (if-let [object (get-in event-bundle [:commit :object])]
    (-> (assoc-in event-bundle [:commit :objects] [object])
        (update-in [:commit] dissoc :object))
    event-bundle))

(defn v2->v1
  ;; make any adjustments needed to make a (maybe) v2 payload into a v1 payload
  [event-bundle]
  (let [result (update-in event-bundle [:commit] dissoc :ip-address)]
    (if-let [objects (get-in event-bundle [:commit :objects])]
      (-> (assoc-in result [:commit :object] (first objects))
          (update-in [:commit] dissoc :objects))
      result)))

(defn report-activity
  [context event-bundle]
  (let [activity-client (:activity-client context)
        supports-v2? (:supports-v2-api context)]
    (if @supports-v2?
      (let [v2-bundle (v1->v2 event-bundle)
            result (activity-client :post "/v2/events" {:body v2-bundle})]
        ;; if we get a 404, the activity service doesn't support the v2 endpoint, so
        ;; cache that, and retry.
        (if (= 404 (:status result))
          (do
            (log/info "Configured activity service does not support v2 API, falling back to v1")
            (swap! supports-v2? (constantly false))
            (report-activity context event-bundle))
          result))
      (let [v1-bundle (v2->v1 event-bundle)]
       (activity-client :post "/v1/events" {:body v1-bundle})))))

(defservice remote-activity-reporter
  "service to report to a remote activity service"
  ActivityReportingService
  [[:ConfigService get-in-config]]
  (init [this context]
    (let [api-url (get-in-config [:activity-consumer :api-url])
          ssl-config (get-in-config [:global :certs])
          authenticated-connection-limits {:max-connections-per-route (get-in-config [:activity-consumer :max-connections-per-route-auth] 20)
                                           :max-connections-total (get-in-config [:activity-consumer :max-connections-total-auth] 20)}
          client (create-client (merge authenticated-connection-limits ssl-config))]
      (log/info (i18n/trs "Connection limit per route for authenticated clients has been set to {0} for the activity service." (:max-connections-per-route authenticated-connection-limits)))
      (log/info (i18n/trs "Total connection limit for authenticated clients has been set to {0} for the activity service." (:max-connections-total authenticated-connection-limits)))
      (assoc context
             :client client
             :supports-v2-api (atom true)
             :activity-client (partial json-api-caller client api-url))))

  (stop [this context]
    (if-let [client (-> this service-context :client)]
      (http/close client))
    context)

  (report-activity! [this event-bundle]
    (let [context (service-context this)]
      (report-activity context event-bundle))))