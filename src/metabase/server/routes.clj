(ns metabase.server.routes
  "Main Compojure routes tables. See https://github.com/weavejester/compojure/wiki/Routes-In-Detail for details about
   how these work. `/api/` routes are in [[metabase.api-routes.routes]]."
  (:require
   [compojure.core :refer #_{:clj-kondo/ignore [:discouraged-var]} [context defroutes GET OPTIONS]]
   [compojure.route :as route]
   [metabase.api-routes.core :as api]
   [metabase.api.dataset :as api.dataset]
   [metabase.core.initialization-status :as init-status]
   [metabase.db :as mdb]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.server.auth-wrapper :as auth-wrapper]
   [metabase.server.routes.index :as index]
   [metabase.settings.deprecated-grab-bag :as public-settings]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [ring.util.response :as response]))

(defn- redirect-including-query-string
  "Like `response/redirect`, but passes along query string URL params as well. This is important because the public and
   embedding routes below pass query params (such as template tags) as part of the URL."
  [url]
  (fn [{:keys [query-string]} respond _raise]
    (respond (response/redirect (str url "?" query-string)))))

;; /public routes. /public/question/:uuid.:export-format redirects to /api/public/card/:uuid/query/:export-format
#_{:clj-kondo/ignore [:discouraged-var]}
(defroutes ^:private ^{:arglists '([request respond raise])} public-routes
  (GET ["/question/:uuid.:export-format", :uuid u/uuid-regex, :export-format api.dataset/export-format-regex]
    [uuid export-format]
    (redirect-including-query-string (format "%s/api/public/card/%s/query/%s" (public-settings/site-url) uuid export-format)))
  (GET "*" [] index/public))

;; /embed routes. /embed/question/:token.:export-format redirects to /api/public/card/:token/query/:export-format
#_{:clj-kondo/ignore [:discouraged-var]}
(defroutes ^:private ^{:arglists '([request respond raise])} embed-routes
  (GET ["/question/:token.:export-format", :export-format api.dataset/export-format-regex]
    [token export-format]
    (redirect-including-query-string (format "%s/api/embed/card/%s/query/%s" (public-settings/site-url) token export-format)))
  (GET "*" [] index/embed))

#_{:clj-kondo/ignore [:discouraged-var]}
(defroutes ^{:doc "Top-level ring routes for Metabase.", :arglists '([request respond raise])} routes
  auth-wrapper/routes
  ;; ^/$ -> index.html
  (GET "/" [] index/index)
  (GET "/favicon.ico" [] (response/resource-response (public-settings/application-favicon-url)))
  ;; ^/api/health -> Health Check Endpoint
  (GET "/api/health" []
    (if (init-status/complete?)
      (try (if (or (mdb/recent-activity?)
                   (sql-jdbc.conn/can-connect-with-spec? {:datasource (mdb/data-source)}))
             {:status 200, :body {:status "ok"}}
             {:status 503 :body {:status "Unable to get app-db connection"}})
           (catch Exception e
             (log/warn e "Error in api/health database check")
             {:status 503 :body {:status "Error getting app-db connection"}}))
      {:status 503, :body {:status "initializing", :progress (init-status/progress)}}))

  (OPTIONS "/api/*" [] {:status 200 :body ""})

  ;; ^/api/ -> All other API routes
  (context "/api" [] (fn [request respond raise]
                       ;; Redirect naughty users who try to visit a page other than setup if setup is not yet complete
                       ;;
                       ;; if Metabase is not finished initializing, return a generic error message rather than
                       ;; something potentially confusing like "DB is not set up"
                       (if-not (init-status/complete?)
                         (respond {:status 503, :body "Metabase is still initializing. Please sit tight..."})
                         (api/routes request respond raise))))
  ;; ^/app/ -> static files under frontend_client/app
  (context "/app" []
    (route/resources "/" {:root "frontend_client/app"})
    ;; return 404 for anything else starting with ^/app/ that doesn't exist
    (route/not-found {:status 404, :body "Not found."}))
  ;; ^/public/ -> Public frontend and download routes
  (context "/public" [] public-routes)
  ;; ^/emebed/ -> Embed frontend and download routes
  (context "/embed" [] embed-routes)
  ;; Anything else (e.g. /user/edit_current) should serve up index.html; React app will handle the rest
  (GET "*" [] index/index))
