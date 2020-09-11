(ns puppetlabs.rbac-client.testutils.dummy-rbac-service
  (:require [puppetlabs.rbac-client.protocols.rbac :refer [RbacConsumerService]]
            [puppetlabs.trapperkeeper.services :refer  [defservice]]
            [slingshot.slingshot :refer  [throw+]]))

(def dummy-rbac (reify RbacConsumerService
                  (is-permitted? [this subject perm-str] true)
                  (are-permitted? [this subject perm-strs]
                    (vec (repeat (count perm-strs) true)))
                  (cert-whitelisted? [this ssl-client-cn] true)
                  (valid-token->subject [this jwt-str]
                    (if (or (not jwt-str) (= "invalid-token" jwt-str))
                      (throw+ {:kind :puppetlabs.rbac/invalid-token
                               :msg (format "Token: %s" jwt-str)})
                      {:login "test_user"
                       :id #uuid "751a8f7e-b53a-4ccd-9f4f-e93db6aa38ec"}))
                  (status [this level]
                    {:service_version "1.2.12",
                     :service_status_version 1,
                     :detail_level "info",
                     :state :running,
                     :status {:db_up true,
                              :activity_up true}})
                  (list-permitted [this token object-type action]
                    ["one", "two", "three"])
                  (list-permitted-for [this subject object-type action]
                    ["four" "five" "six"])))


(defservice dummy-rbac-service
  RbacConsumerService
  []
  (is-permitted? [this subject perm-str] true)
  (are-permitted? [this subject perm-strs]
                  (vec (repeat (count perm-strs) true)))
  (cert-whitelisted? [this ssl-client-cn] true)
  (cert-allowed? [this ssl-client-cn] true)
  (cert->subject [this ssl-client-cn]
    {:id #uuid "af94921f-bd76-4b58-b5ce-e17c029a2790"
     :login "api_user"})
  (valid-token->subject [this jwt-str]
    (if (or (not jwt-str) (= "invalid-token" jwt-str))
      (throw+ {:kind :puppetlabs.rbac/invalid-token
               :msg (format "Token: %s" jwt-str)})
      {:login     "test_user"
       :id        #uuid "751a8f7e-b53a-4ccd-9f4f-e93db6aa38ec"
       :group_ids [#uuid "aaaaaaaa-b53a-4ccd-9f4f-e93db6aa38ec"
                   #uuid "bbbbbbbb-b53a-4ccd-9f4f-e93db6aa38ec"]}))
  (status [this level]
          {:service_version "1.2.12",
           :service_status_version 1,
           :detail_level "info",
           :state :running,
           :status {:db_up true,
                    :activity_up true}})
  (list-permitted [this token object-type action]
                  ["one", "two", "three"])
  (list-permitted-for [this subject object-type action]
                      ["four" "five" "six"])
  (subject [this user-id]
           {:id user-id
            :login "anImaginaryUserForTesting"}))