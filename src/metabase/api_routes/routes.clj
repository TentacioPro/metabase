(ns metabase.api-routes.routes
  (:require
   [compojure.route :as route]
   [metabase.actions.api]
   [metabase.activity-feed.api]
   [metabase.api-keys.api]
   [metabase.api.card]
   [metabase.api.cards]
   [metabase.api.collection]
   [metabase.api.dashboard]
   [metabase.api.database]
   [metabase.api.dataset]
   [metabase.api.docs]
   [metabase.api.field]
   [metabase.api.geojson]
   [metabase.api.logger]
   [metabase.api.macros :as api.macros]
   [metabase.api.open-api :as open-api]
   [metabase.api.routes.common :as routes.common :refer [+static-apikey]]
   [metabase.api.table]
   [metabase.api.user]
   [metabase.api.util]
   [metabase.api.util.handlers :as handlers]
   [metabase.bookmarks.api]
   [metabase.cache.api]
   [metabase.channel.api]
   [metabase.cloud-migration.api]
   [metabase.config :as config]
   [metabase.embedding.api]
   [metabase.indexed-entities.api]
   [metabase.login-history.api]
   [metabase.model-persistence.api]
   [metabase.native-query-snippets.api]
   [metabase.notification.api]
   [metabase.permissions.api]
   [metabase.premium-features.api]
   [metabase.public-sharing.api]
   [metabase.pulse.api]
   [metabase.revisions.api]
   [metabase.search.api]
   [metabase.segments.api]
   [metabase.session.api]
   [metabase.settings.api]
   [metabase.setup.api]
   [metabase.sso.api]
   [metabase.sync.api]
   [metabase.task-history.api]
   [metabase.testing-api.api]
   [metabase.testing-api.core]
   [metabase.tiles.api]
   [metabase.timeline.api]
   [metabase.upload.api]
   [metabase.user-key-value.api]
   [metabase.util.i18n :refer [deferred-tru]]
   [metabase.xrays.api]))

(comment metabase.actions.api/keep-me
         metabase.activity-feed.api/keep-me
         metabase.api-keys.api/keep-me
         metabase.api.card/keep-me
         metabase.api.cards/keep-me
         metabase.api.collection/keep-me
         metabase.api.dashboard/keep-me
         metabase.api.database/keep-me
         metabase.api.dataset/keep-me
         metabase.api.field/keep-me
         metabase.api.geojson/keep-me
         metabase.api.logger/keep-me
         metabase.api.table/keep-me
         metabase.api.user/keep-me
         metabase.api.util/keep-me
         metabase.bookmarks.api/keep-me
         metabase.cache.api/keep-me
         metabase.cloud-migration.api/keep-me
         metabase.indexed-entities.api/keep-me
         metabase.login-history.api/keep-me
         metabase.model-persistence.api/keep-me
         metabase.native-query-snippets.api/keep-me
         metabase.permissions.api/keep-me
         metabase.public-sharing.api/keep-me
         metabase.revisions.api/keep-me
         metabase.segments.api/keep-me
         metabase.settings.api/keep-me
         metabase.setup.api/keep-me
         metabase.task-history.api/keep-me
         metabase.testing-api.api/keep-me
         metabase.tiles.api/keep-me
         metabase.upload.api/keep-me
         metabase.user-key-value.api/keep-me)

(def ^:private ^{:arglists '([request respond raise])} pass-thru-handler
  "Always 'falls thru' to the next handler."
  (open-api/handler-with-open-api-spec
   (fn [_request respond _raise]
     (respond nil))
   ;; no OpenAPI spec for this handler.
   (fn [_prefix]
     nil)))

(def ^:private ^{:arglists '([request respond raise])} not-found-handler
  "Always returns a 404."
  (open-api/handler-with-open-api-spec
   (route/not-found (constantly {:status 404, :body (deferred-tru "API endpoint does not exist.")}))
   ;; no OpenAPI spec for this handler.
   (fn [_prefix]
     nil)))

(defn- ->handler [x]
  (cond-> x
    (simple-symbol? x) api.macros/ns-handler))

(defn- +auth                    [handler] (routes.common/+auth                    (->handler handler)))
(defn- +message-only-exceptions [handler] (routes.common/+message-only-exceptions (->handler handler)))
(defn- +public-exceptions       [handler] (routes.common/+public-exceptions       (->handler handler)))

(declare routes)

;;; !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
;;; !!                                                                                                !!
;;; !!                  DO NOT ADD `metabase.api.*` NAMESPACES THAT CONTAIN ENDPOINTS                 !!
;;; !!                                                                                                !!
;;; !!   Please read https://metaboat.slack.com/archives/CKZEMT1MJ/p1738972144181069 for more info    !!
;;; !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

;;; ↓↓↓ KEEP THIS SORTED OR ELSE! ↓↓↓
(def ^:private route-map
  {"/action"               (+auth 'metabase.actions.api)
   "/activity"             (+auth 'metabase.activity-feed.api)
   "/alert"                (+auth metabase.pulse.api/alert-routes)
   "/api-key"              (+auth 'metabase.api-keys.api)
   "/automagic-dashboards" (+auth metabase.xrays.api/automagic-dashboards-routes)
   "/bookmark"             (+auth 'metabase.bookmarks.api)
   "/cache"                (+auth 'metabase.cache.api)
   "/card"                 (+auth 'metabase.api.card)
   "/cards"                (+auth 'metabase.api.cards)
   "/channel"              (+auth metabase.channel.api/channel-routes)
   "/cloud-migration"      (+auth 'metabase.cloud-migration.api)
   "/collection"           (+auth 'metabase.api.collection)
   "/dashboard"            (+auth 'metabase.api.dashboard)
   "/database"             (+auth 'metabase.api.database)
   "/dataset"              'metabase.api.dataset
   "/docs"                 (metabase.api.docs/make-routes #'routes)
   "/email"                metabase.channel.api/email-routes
   "/embed"                (+message-only-exceptions metabase.embedding.api/embedding-routes)
   "/field"                (+auth 'metabase.api.field)
   "/geojson"              'metabase.api.geojson
   "/google"               (+auth metabase.sso.api/google-auth-routes)
   "/ldap"                 (+auth metabase.sso.api/ldap-routes)
   "/logger"               (+auth 'metabase.api.logger)
   "/login-history"        (+auth 'metabase.login-history.api)
   "/model-index"          (+auth 'metabase.indexed-entities.api)
   "/native-query-snippet" (+auth 'metabase.native-query-snippets.api)
   "/notification"         metabase.notification.api/notification-routes
   "/notify"               (+static-apikey metabase.sync.api/notify-routes)
   "/permissions"          (+auth 'metabase.permissions.api)
   "/persist"              (+auth 'metabase.model-persistence.api)
   "/premium-features"     (+auth metabase.premium-features.api/routes)
   "/preview_embed"        (+auth metabase.embedding.api/preview-embedding-routes)
   "/public"               (+public-exceptions 'metabase.public-sharing.api)
   "/pulse"                metabase.pulse.api/pulse-routes
   "/revision"             (+auth 'metabase.revisions.api)
   "/search"               (+auth metabase.search.api/routes)
   "/segment"              (+auth 'metabase.segments.api)
   "/session"              metabase.session.api/routes
   "/setting"              (+auth 'metabase.settings.api)
   "/setup"                'metabase.setup.api
   "/slack"                (+auth metabase.channel.api/slack-routes)
   "/table"                (+auth 'metabase.api.table)
   "/task"                 (+auth 'metabase.task-history.api)
   "/testing"              (if metabase.testing-api.core/enable-testing-routes? 'metabase.testing-api.api pass-thru-handler)
   "/tiles"                (+auth 'metabase.tiles.api)
   "/timeline"             (+auth metabase.timeline.api/timeline-routes)
   "/timeline-event"       (+auth metabase.timeline.api/timeline-event-routes)
   "/upload"               (+auth 'metabase.upload.api)
   "/user"                 (+auth 'metabase.api.user)
   "/user-key-value"       (+auth 'metabase.user-key-value.api)
   "/util"                 'metabase.api.util})
;;; ↑↑↑ KEEP THIS SORTED OR ELSE ↑↑↑

;;; !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
;;; !!                                                                                                !!
;;; !!                  DO NOT ADD `metabase.api.*` NAMESPACES THAT CONTAIN ENDPOINTS                 !!
;;; !!                                                                                                !!
;;; !!   Please read https://metaboat.slack.com/archives/CKZEMT1MJ/p1738972144181069 for more info    !!
;;; !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

(def ^{:arglists '([request respond raise])} routes
  "Ring routes for API endpoints."
  ;; EE routes defined in [[metabase-enterprise.api.routes/routes]] always get the first chance to handle a request, if
  ;; they exist. If they don't exist, this handler returns `nil` which means we will try the next handler.
  (handlers/routes
   (if (and config/ee-available? (not *compile-files*))
     (requiring-resolve 'metabase-enterprise.api.routes/routes)
     pass-thru-handler)
   (handlers/route-map-handler route-map)
   not-found-handler))
