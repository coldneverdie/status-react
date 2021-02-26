(ns status-im.chat.models.message
  (:require [status-im.chat.models :as chat-model]
            [status-im.chat.models.message-list :as message-list]
            [status-im.constants :as constants]
            [status-im.data-store.messages :as data-store.messages]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.transport.message.protocol :as protocol]
            [status-im.utils.fx :as fx]
            [taoensso.timbre :as log]
            [status-im.chat.models.mentions :as mentions]
            [clojure.string :as string]
            [status-im.contact.db :as contact.db]
            [status-im.utils.types :as types]
            [status-im.ui.screens.chat.state :as view.state]
            [status-im.chat.models.loading :as chat.loading]
            [status-im.utils.platform :as platform]))

(defn- message-loaded?
  [db chat-id message-id]
  (get-in db [:messages chat-id message-id]))

(defn- earlier-than-deleted-at?
  [db chat-id clock-value]
  (>= (get-in db [:chats chat-id :deleted-at-clock-value]) clock-value))

(defn add-timeline-message [acc chat-id message-id message]
  (-> acc
      (update-in [:db :messages chat-id] assoc message-id message)
      (update-in [:db :message-lists chat-id] message-list/add message)))

;;TODO this is too expensive, probably we could mark message somehow and just hide it in the UI
(fx/defn rebuild-message-list
  [{:keys [db]} chat-id]
  {:db (assoc-in db [:message-lists chat-id]
                 (message-list/add-many nil (vals (get-in db [:messages chat-id]))))})

(defn hide-message
  "Hide chat message, rebuild message-list"
  [{:keys [db]} chat-id message-id]
  ;;TODO this is too expensive, probably we could mark message somehow and just hide it in the UI
  (rebuild-message-list {:db (update-in db [:messages chat-id] dissoc message-id)} chat-id))

(fx/defn join-times-messages-checked
  "The key :might-have-join-time-messages? in public chats signals that
  the public chat is freshly (re)created and requests for messages to the
  mailserver for the topic has not completed yet. Likewise, the key
  :join-time-mail-request-id is associated a little bit after, to signal that
  the request to mailserver was a success. When request is signalled complete
  by mailserver, corresponding event :chat.ui/join-times-messages-checked
  dissociates these two fileds via this function, thereby signalling that the
  public chat is not fresh anymore."
  {:events [:chat/join-times-messages-checked]}
  [{:keys [db] :as cofx} chat-ids]
  (reduce (fn [acc chat-id]
            (cond-> acc
              (:might-have-join-time-messages? (chat-model/get-chat cofx chat-id))
              (update :db #(chat-model/dissoc-join-time-fields % chat-id))))
          {:db db}
          chat-ids))

(fx/defn add-senders-to-chat-users
  {:events [:chat/add-senders-to-chat-users]}
  [{:keys [db]} messages]
  (reduce (fn [acc {:keys [chat-id alias name identicon from]}]
            (update-in acc [:db :chats chat-id :users] assoc
                       from
                       (mentions/add-searchable-phrases
                        {:alias      alias
                         :name       (or name alias)
                         :identicon  identicon
                         :public-key from
                         :nickname   (get-in db [:contacts/contacts from :nickname])})))
          {:db db}
          messages))

(defn get-timeline-message [db chat-id message-js]
  (when (and
         (get-in db [:pagination-info constants/timeline-chat-id :messages-initialized?])
         (when-let [pub-key (get-in db [:chats chat-id :profile-public-key])]
           (contact.db/added? db pub-key)))
    (data-store.messages/<-rpc (types/js->clj message-js))))

(defn add-message [db timeline-message message-js chat-id message-id acc cursor-clock-value]
  (let [{:keys [alias replace from clock-value] :as message}
        (or timeline-message (data-store.messages/<-rpc (types/js->clj message-js)))]
    (if (message-loaded? db chat-id message-id)
      ;; If the message is already loaded, it means it's an update, that
      ;; happens when a message that was missing a reply had the reply
      ;; coming through, in which case we just insert the new message
      (assoc-in acc [:db :messages chat-id message-id] message)
      (cond-> acc
        ;;add new message to db
        :always
        (update-in [:db :messages chat-id] assoc message-id message)
        :always
        (update-in [:db :message-lists chat-id] message-list/add message)

        (or (not cursor-clock-value) (< clock-value cursor-clock-value))
        (update-in [:db :pagination-info chat-id] assoc
                   :cursor (chat.loading/clock-value->cursor clock-value)
                   :cursor-clock-value clock-value)

        ;;conj sender for add-sender-to-chat-users
        (and (not (string/blank? alias))
             (not (get-in db [:chats chat-id :users from])))
        (update :senders assoc from message)

        (not (string/blank? replace))
        ;;TODO this is expensive
        (hide-message chat-id replace)))))

(defn reduce-js-messages [{:keys [db] :as acc} ^js message-js]
  (let [chat-id (.-localChatId message-js)
        clock-value (.-clock message-js)
        message-id (.-id message-js)
        current-chat-id (:current-chat-id db)
        cursor-clock-value (get-in db [:pagination-info current-chat-id :cursor-clock-value])
        timeline-message (get-timeline-message db chat-id message-js)
        ;;add timeline message
        {:keys [db] :as acc} (if timeline-message
                               (add-timeline-message acc chat-id message-id timeline-message)
                               acc)]
    ;;ignore not opened chats and earlier clock
    (if (and (get-in db [:pagination-info chat-id :messages-initialized?])
             ;;TODO why do we need this ?
             (not (earlier-than-deleted-at? db chat-id clock-value)))
      (if (or (not @view.state/first-not-visible-item)
              (<= (:clock-value @view.state/first-not-visible-item)
                  clock-value))
        (add-message db timeline-message message-js chat-id message-id acc cursor-clock-value)
        ;; Not in the current view, set all-loaded to false
        ;; and offload to db and update cursor if necessary
        {:db (cond-> (assoc-in db [:pagination-info chat-id :all-loaded?] false)
               (> clock-value cursor-clock-value)
               ;;TODO cut older messages from messages-list
               (update-in [:pagination-info chat-id] assoc
                          :cursor (chat.loading/clock-value->cursor clock-value)
                          :cursor-clock-value clock-value))})
      acc)))

(defn receive-many [{:keys [db]} ^js response-js]
  ;; we use 10 here , because of slow devices, and flatlist initrenderitem number is 10
  (let [current-chat-id (:current-chat-id db)
        messages-js ^js (.splice (.-messages response-js) 0 (if platform/low-device? 3 10))
        {:keys [db chats senders]}
        (reduce reduce-js-messages
                {:db db :chats #{} :senders {} :transactions #{}}
                messages-js)]
    ;;we want to render new messages as soon as possible
    ;;so we dispatch later all other events which can be handled async
    {:utils/dispatch-later
     (concat [{:ms 20 :dispatch [:process-response response-js]}]
             (when (and current-chat-id
                        (get chats current-chat-id)
                        (not (chat-model/profile-chat? {:db db} current-chat-id)))
               [{:ms 30 :dispatch [:chat/mark-all-as-read (:current-chat-id db)]}])
             (when (seq senders)
               [{:ms 40 :dispatch [:chat/add-senders-to-chat-users (vals senders)]}]))
     :db (assoc-in db [:pagination-info current-chat-id :processing?]
                   (> (count (.-messages response-js)) 0))}))

(fx/defn update-db-message-status
  [{:keys [db] :as cofx} chat-id message-id status]
  (when (get-in db [:messages chat-id message-id])
    (fx/merge cofx
              {:db (assoc-in db
                             [:messages chat-id message-id :outgoing-status]
                             status)})))

(fx/defn update-message-status
  [{:keys [db] :as cofx} chat-id message-id status]
  (fx/merge cofx
            (update-db-message-status chat-id message-id status)
            (data-store.messages/update-outgoing-status message-id status)))

(fx/defn resend-message
  [{:keys [db] :as cofx} chat-id message-id]
  (fx/merge cofx
            {::json-rpc/call [{:method (json-rpc/call-ext-method "reSendChatMessage")
                               :params [message-id]
                               :on-success #(log/debug "re-sent message successfully")
                               :on-error #(log/error "failed to re-send message" %)}]}
            (update-message-status chat-id message-id :sending)))

(fx/defn delete-message
  "Deletes chat message, rebuild message-list"
  {:events [:chat.ui/delete-message]}
  [{:keys [db] :as cofx} chat-id message-id]
  (fx/merge cofx
            {:db            (update-in db [:messages chat-id] dissoc message-id)}
            (data-store.messages/delete-message message-id)
            (rebuild-message-list chat-id)))

(fx/defn send-message
  [cofx message]
  (protocol/send-chat-messages cofx [message]))

(fx/defn send-messages
  [cofx messages]
  (protocol/send-chat-messages cofx messages))
