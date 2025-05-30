(ns metabase.settings.deprecated-grab-bag
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [environ.core :as env]
   [java-time.api :as t]
   [metabase.config :as config]
   [metabase.premium-features.core :as premium-features]
   [metabase.settings.models.setting :as setting :refer [defsetting]]
   [metabase.util :as u]
   [metabase.util.fonts :as u.fonts]
   [metabase.util.i18n :as i18n :refer [available-locales-with-names deferred-tru trs tru]]
   [metabase.util.log :as log]
   [metabase.util.password :as u.password]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defsetting application-name
  (deferred-tru "Replace the word “Metabase” wherever it appears.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel
  :default    "Metabase")

(defn application-name-for-setting-descriptions
  "Returns the value of the [[application-name]] setting so setting docstrings can be generated during the compilation stage.
   Use this instead of `application-name` in descriptions, otherwise the `application-name` setting's
   `:enabled?` function will be called during compilation, which will fail because it will attempt to perform i18n, which is
   not allowed during compilation."
  []
  (if *compile-files*
    "Metabase"
    (binding [config/*disable-setting-cache* true]
      (application-name))))

(defn google-auth-enabled?
  "Is Google Auth (OIDC not SAML) enabled?"
  []
  (boolean (setting/get :google-auth-enabled)))

(defn ldap-enabled?
  "Is LDAP enabled?"
  []
  (setting/get :ldap-enabled))

(defn- ee-sso-configured? []
  (when config/ee-available?
    (setting/get :other-sso-enabled?)))

;;; TODO -- consider whether this belongs here or in the `sso` module
(defn sso-enabled?
  "Any SSO provider is configured and enabled"
  []
  (or (google-auth-enabled?)
      (ldap-enabled?)
      (ee-sso-configured?)))

(defsetting check-for-updates
  (deferred-tru "Identify when new versions of Metabase are available.")
  :type    :boolean
  :audit   :getter
  :default true)

(defn- set-update-channel! [new-channel]
  (let [valid-channels #{"latest" "beta" "nightly"}]
    (when-not (valid-channels new-channel)
      (throw (IllegalArgumentException.
              (tru "Invalid update channel ''{0}''. Valid channels are: {1}"
                   new-channel valid-channels))))
    (setting/set-value-of-type! :string :update-channel new-channel)))

(defsetting update-channel
  (deferred-tru "We''ll notify you here when there''s a new version of this type of release.")
  :visibility :admin
  :type       :string
  :encryption :no
  :export?    true
  :audit      :getter
  :setter     set-update-channel!
  :default    "latest")

(defsetting site-uuid
  ;; Don't i18n this docstring because it's not user-facing! :)
  "Unique identifier used for this instance of {0}. This is set once and only once the first time it is fetched via
  its magic getter. Nice!"
  :encryption :no
  :visibility :authenticated
  :base       setting/uuid-nonce-base
  :doc        false)

(defsetting upgrade-threshold
  (deferred-tru "Threshold (value in 0-100) indicating at which treshold it should offer an upgrade to the latest major version.")
  :visibility :internal
  :export?    false
  :type       :integer
  :setter     :none
  :getter     (fn []
                ;; site-uuid is stable, current-major lets the threshold randomize during each major revision. So they
                ;; might be early one release, and then later the next.
                (-> (site-uuid) (str "-" (config/current-major-version)) hash (mod 100))))

(defn- prevent-upgrade?
  "On a major upgrade, we check the rollout threshold to indicate whether we should remove the latest release from the
  version info. This lets us stage upgrade notifications to self-hosted instances in a controlled manner. Defaults to
  show the upgrade except under certain circumstances."
  [current-major latest threshold]
  (when (and (integer? current-major) (integer? threshold) (string? (:version latest)))
    (try (let [upgrade-major (-> latest :version config/major-version)
               rollout       (some-> latest :rollout)]
           (when (and upgrade-major rollout)
             (cond
               ;; it's the same or a minor release
               (= upgrade-major current-major) false
               ;; the rollout threshold is larger than our threshold
               (>= rollout threshold) false
               :else true)))
         (catch Exception _e true))))

(defn- version-info*
  [raw-version-info {:keys [current-major upgrade-threshold-value]}]
  (try
    (cond-> raw-version-info
      (prevent-upgrade? current-major (-> raw-version-info :latest) upgrade-threshold-value)
      (dissoc :latest))
    (catch Exception e
      (log/error e "Error processing version info")
      raw-version-info)))

(defsetting version-info
  (deferred-tru "Information about available versions of Metabase.")
  :encryption :no
  :type       :json
  :audit      :never
  :default    {}
  :doc        false
  :getter     (fn []
                (let [raw-vi (setting/get-value-of-type :json :version-info)
                      current-major (config/current-major-version)]
                  (version-info* raw-vi {:current-major current-major :upgrade-threshold-value (upgrade-threshold)})))
  :include-in-list? false)

(defsetting version-info-last-checked
  (deferred-tru "Indicates when Metabase last checked for new versions.")
  :visibility :public
  :type       :timestamp
  :audit      :never
  :default    nil
  :doc        false)

(defsetting startup-time-millis
  (deferred-tru "The startup time in milliseconds")
  :visibility :public
  :type       :double
  :audit      :never
  :default    0.0
  :doc        false)

(defsetting site-name
  (deferred-tru "The name used for this instance of {0}."
                (application-name-for-setting-descriptions))
  :encryption :no
  :default    "Metabase"
  :audit      :getter
  :visibility :settings-manager
  :export?    true)

(def ^:private default-allowed-iframe-hosts
  "youtube.com,
youtu.be,
loom.com,
vimeo.com,
docs.google.com,
calendar.google.com,
airtable.com,
typeform.com,
canva.com,
codepen.io,
figma.com,
grafana.com,
miro.com,
excalidraw.com,
notion.com,
atlassian.com,
trello.com,
asana.com,
gist.github.com,
linkedin.com,
twitter.com,
x.com")

(defsetting allowed-iframe-hosts
  (deferred-tru "Allowed iframe hosts")
  :encryption :no
  :default    default-allowed-iframe-hosts
  :audit      :getter
  :visibility :public
  :export?    true)

(defsetting custom-homepage
  (deferred-tru "Pick one of your dashboards to serve as homepage. Users without dashboard access will be directed to the default homepage.")
  :encryption :no
  :default    false
  :type       :boolean
  :audit      :getter
  :visibility :public)

(defsetting custom-homepage-dashboard
  (deferred-tru "ID of dashboard to use as a homepage")
  :encryption :no
  :type       :integer
  :visibility :public
  :audit      :getter)

(defsetting site-uuid-for-version-info-fetching
  "A *different* site-wide UUID that we use for the version info fetching API calls. Do not use this for any other
  applications. (See [[metabase.premium-features.settings/site-uuid-for-premium-features-token-checks]] for more
  reasoning.)"
  :encryption :when-encryption-key-set
  :visibility :internal
  :base       setting/uuid-nonce-base)

(defsetting site-uuid-for-unsubscribing-url
  "UUID that we use for generating urls users to unsubscribe from alerts. The hash is generated by
  hash(secret_uuid + email + subscription_id) = url. Do not use this for any other applications. (See #29955)"
  :encryption :when-encryption-key-set
  :visibility :internal
  :base       setting/uuid-nonce-base)

(defn- normalize-site-url [^String s]
  (let [;; remove trailing slashes
        s (str/replace s #"/$" "")
        ;; add protocol if missing
        s (if (str/starts-with? s "http")
            s
            (str "http://" s))]
    ;; check that the URL is valid
    (when-not (u/url? s)
      (throw (ex-info (tru "Invalid site URL: {0}" (pr-str s)) {:url (pr-str s)})))
    s))

(declare redirect-all-requests-to-https!)

;; This value is *guaranteed* to never have a trailing slash :D
;; It will also prepend `http://` to the URL if there's no protocol when it comes in
(defsetting site-url
  (deferred-tru
   (str "This URL is used for things like creating links in emails, auth redirects, and in some embedding scenarios, "
        "so changing it could break functionality or get you locked out of this instance."))
  :encryption :when-encryption-key-set
  :visibility :public
  :audit      :getter
  :getter     (fn []
                (try
                  (some-> (setting/get-value-of-type :string :site-url) normalize-site-url)
                  (catch clojure.lang.ExceptionInfo e
                    (log/error e "site-url is invalid; returning nil for now. Will be reset on next request."))))
  :setter     (fn [new-value]
                (let [new-value (some-> new-value normalize-site-url)
                      https?    (some-> new-value (str/starts-with?  "https:"))]
                  ;; if the site URL isn't HTTPS then disable force HTTPS redirects if set
                  (when-not https?
                    (redirect-all-requests-to-https! false))
                  (setting/set-value-of-type! :string :site-url new-value)))
  :doc "This URL is critical for things like SSO authentication, email links, embedding and more.
        Even difference with `http://` vs `https://` can cause problems.
        Make sure that the address defined is how Metabase is being accessed.")

(defsetting site-locale
  (deferred-tru
   (str "The default language for all users across the {0} UI, system emails, pulses, and alerts. "
        "Users can individually override this default language from their own account settings.")
   (application-name-for-setting-descriptions))
  :default    "en"
  :visibility :public
  :export?    true
  :audit      :getter
  :encryption :no
  :getter     (fn []
                (let [value (setting/get-value-of-type :string :site-locale)]
                  (when (i18n/available-locale? value)
                    value)))
  :setter     (fn [new-value]
                (when new-value
                  (when-not (i18n/available-locale? new-value)
                    (throw (ex-info (tru "Invalid locale {0}" (pr-str new-value)) {:status-code 400}))))
                (setting/set-value-of-type! :string :site-locale (some-> new-value i18n/normalized-locale-string))))

(defsetting admin-email
  (deferred-tru "The email address users should be referred to if they encounter a problem.")
  :visibility :authenticated
  :encryption :when-encryption-key-set
  :audit      :getter)

(defsetting anon-tracking-enabled
  (deferred-tru "Enable the collection of anonymous usage data in order to help {0} improve."
                (application-name-for-setting-descriptions))
  :type       :boolean
  :default    true
  :visibility :public
  :audit      :getter)

(defn- coerce-to-relative-url
  "Get the path of a given URL if the URL contains an origin.
   Otherwise make the landing-page a relative path."
  [landing-page]
  (cond
    (u/url? landing-page) (-> landing-page io/as-url .getPath)
    (empty? landing-page) ""
    (not (str/starts-with? landing-page "/")) (str "/" landing-page)
    :else landing-page))

(defsetting landing-page
  (deferred-tru "Enter a URL of the landing page to show the user. This overrides the custom homepage setting above.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :default    ""
  :audit      :getter
  :setter     (fn [new-landing-page]
                (when new-landing-page
                  ;; If the landing page is a valid URL or mailto, sms, or file, then check with if site-url has the same origin.
                  (when (and (or (re-matches #"^(mailto|sms|file):(.*)" new-landing-page) (u/url? new-landing-page))
                             (not (str/starts-with? new-landing-page (site-url))))
                    (throw (ex-info (tru "This field must be a relative URL.") {:status-code 400}))))
                (setting/set-value-of-type! :string :landing-page (coerce-to-relative-url new-landing-page))))

(defsetting enable-pivoted-exports
  (deferred-tru "Enable pivoted exports and pivoted subscriptions")
  :type       :boolean
  :default    true
  :export?    true
  :visibility :authenticated
  :audit      :getter)

(defsetting enable-nested-queries
  (deferred-tru "Allow using a saved question or Model as the source for other queries?")
  :type       :boolean
  :default    true
  :setter     :none
  :visibility :authenticated
  :export?    true
  :getter     (fn enable-nested-queries-getter []
                ;; only false if explicitly set `false` by the environment
                (not= "false" (u/lower-case-en (env/env :mb-enable-nested-queries))))
  :audit      :getter)

(defsetting notification-link-base-url
  (deferred-tru "By default \"Site Url\" is used in notification links, but can be overridden.")
  :encryption :no
  :visibility :internal
  :type       :string
  :feature    :whitelabel
  :audit      :getter
  :doc "The base URL where dashboard notitification links will point to instead of the Metabase base URL.
        Only applicable for users who utilize interactive embedding and subscriptions.")

(defsetting deprecation-notice-version
  (deferred-tru "Metabase version for which a notice about usage of deprecated features has been shown.")
  :encryption :no
  :visibility :admin
  :doc        false
  :audit      :never)

(def ^:private loading-message-values
  #{:doing-science :running-query :loading-results})

(defsetting loading-message
  (deferred-tru (str "Choose the message to show while a query is running. Possible values are \"doing-science\", "
                     "\"running-query\", or \"loading-results\""))
  :encryption :no
  :visibility :public
  :export?    true
  :feature    :whitelabel
  :type       :keyword
  :default    :doing-science
  :setter     (fn [new-value]
                (let [value (or (loading-message-values (keyword new-value))
                                (throw (ex-info "Loading message set to an unsupported value"
                                                {:value   new-value
                                                 :options (seq loading-message-values)})))]
                  (setting/set-value-of-type! :keyword :loading-message value)))
  :getter     (fn []
                (let [value (setting/get-value-of-type :keyword :loading-message)]
                  (or (loading-message-values value)
                      :doing-science)))
  :audit      :getter)

(defsetting application-colors
  (deferred-tru "Choose the colors used in the user interface throughout Metabase and others specifically for the charts. You need to refresh your browser to see your changes take effect.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :json
  :feature    :whitelabel
  :default    {}
  :audit      :getter
  :doc "To change the user interface colors:

```
{
 \"brand\":\"#ff003b\",
 \"filter\":\"#FF003B\",
 \"summarize\":\"#FF003B\"
}
```

To change the chart colors:

```
{
 \"accent0\":\"#FF0005\",
 \"accent1\":\"#E6C367\",
 \"accent2\":\"#B9E68A\",
 \"accent3\":\"#8AE69F\",
 \"accent4\":\"#8AE6E4\",
 \"accent5\":\"#8AA2E6\",
 \"accent6\":\"#B68AE6\",
 \"accent7\":\"#E68AD0\"
}
```")

(defsetting application-font
  (deferred-tru "Replace “Lato” as the font family.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :default    "Lato"
  :feature    :whitelabel
  :audit      :getter
  :setter     (fn [new-value]
                (when new-value
                  (when-not (u.fonts/available-font? new-value)
                    (throw (ex-info (tru "Invalid font {0}" (pr-str new-value)) {:status-code 400}))))
                (setting/set-value-of-type! :string :application-font new-value)))

(defsetting application-font-files
  (deferred-tru "Tell us where to find the file for each font weight. You don’t need to include all of them, but it’ll look better if you do.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :json
  :audit      :getter
  :feature    :whitelabel
  :doc "Example value:

```
[
  {
    \"src\": \"https://example.com/resources/font-400\",
    \"fontFormat\": \"ttf\",
    \"fontWeight\": 400
  },
  {
    \"src\": \"https://example.com/resources/font-700\",
    \"fontFormat\": \"woff\",
    \"fontWeight\": 700
  }
]
```

See [fonts](../configuring-metabase/fonts.md).")

(defn application-color
  "The primary color, a.k.a. brand color"
  []
  (or (:brand (application-colors)) "#509EE3"))

(defn secondary-chart-color
  "The first 'Additional chart color'"
  []
  (or (:accent3 (application-colors)) "#EF8C8C"))

(defsetting application-logo-url
  (deferred-tru "Upload a file to replace the Metabase logo on the top bar.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel
  :default    "app/assets/img/logo.svg"
  :doc "Inline styling and inline scripts are not supported.")

(defsetting application-favicon-url
  (deferred-tru "Upload a file to use as the favicon.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel
  :default    "app/assets/img/favicon.ico")

(defsetting show-metabot
  (deferred-tru "Enables Metabot character on the home page")
  :visibility :public
  :export?    true
  :type       :boolean
  :audit      :getter
  :feature    :whitelabel
  :default    true)

(defsetting login-page-illustration
  (deferred-tru "Options for displaying the illustration on the login page.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel
  :default    "default")

(defsetting login-page-illustration-custom
  (deferred-tru "The custom illustration for the login page.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel)

(defsetting landing-page-illustration
  (deferred-tru "Options for displaying the illustration on the landing page.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel
  :default    "default")

(defsetting landing-page-illustration-custom
  (deferred-tru "The custom illustration for the landing page.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel)

(defsetting no-data-illustration
  (deferred-tru "Options for displaying the illustration when there are no results after running a question.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel
  :default    "default")

(defsetting no-data-illustration-custom
  (deferred-tru "The custom illustration for when there are no results after running a question.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel)

(defsetting no-object-illustration
  (deferred-tru "Options for displaying the illustration when there are no results after searching.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel
  :default    "default")

(defsetting no-object-illustration-custom
  (deferred-tru "The custom illustration for when there are no results after searching.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter
  :feature    :whitelabel)

(def ^:private help-link-options
  #{:metabase :hidden :custom})

(defsetting help-link
  (deferred-tru
   (str
    "Keyword setting to control whitelabeling of the help link. Valid values are `:metabase`, `:hidden`, and "
    "`:custom`. If `:custom` is set, the help link will use the URL specified in the `help-link-custom-destination`, "
    "or be hidden if it is not set."))
  :type       :keyword
  :audit      :getter
  :visibility :public
  :feature    :whitelabel
  :default    :metabase
  :setter     (fn [value]
                (when-not (help-link-options (keyword value))
                  (throw (ex-info (tru "Invalid help link option")
                                  {:value value
                                   :valid-options help-link-options})))
                (setting/set-value-of-type! :keyword :help-link value)))

(defn- validate-help-url
  "Checks that the provided URL is either a valid HTTP/HTTPS URL or a `mailto:` link. Returns `nil` if the input is valid;
  throws an exception if it is not."
  [url]
  (let [validation-exception (ex-info (tru "Please make sure this is a valid URL")
                                      {:url url})]
    (if-let [matches (re-matches #"^mailto:(.*)" url)]
      (when-not (u/email? (second matches))
        (throw validation-exception))
      (when-not (u/url? url)
        (throw validation-exception)))))

(defsetting help-link-custom-destination
  (deferred-tru "Custom URL for the help link.")
  :encryption :no
  :visibility :public
  :type       :string
  :audit      :getter
  :default   "https://www.metabase.com/help/premium"
  :feature    :whitelabel
  :setter     (fn [new-value]
                (let [new-value-string (str new-value)]
                  (validate-help-url new-value-string)
                  (setting/set-value-of-type! :string :help-link-custom-destination new-value-string))))

(defsetting show-metabase-links
  (deferred-tru "Whether or not to display Metabase links outside admin settings.")
  :type       :boolean
  :default    true
  :visibility :public
  :audit      :getter
  :feature    :whitelabel)

;;; TODO -- consider whether this belongs here or in the `sso` module
(defsetting enable-password-login
  (deferred-tru "Allow logging in by email and password.")
  :visibility :public
  :type       :boolean
  :default    true
  :feature    :disable-password-login
  :audit      :raw-value
  :getter     (fn []
                ;; if `:enable-password-login` has an *explict* (non-default) value, and SSO is configured, use that;
                ;; otherwise this always returns true.
                (let [v (setting/get-value-of-type :boolean :enable-password-login)]
                  (if (and (some? v)
                           (sso-enabled?))
                    v
                    true))))

(defsetting breakout-bins-num
  (deferred-tru
   (str "When using the default binning strategy and a number of bins is not provided, "
        "this number will be used as the default."))
  :type    :integer
  :export? true
  :default 8
  :audit   :getter)

(defsetting breakout-bin-width
  (deferred-tru
   (str "When using the default binning strategy for a field of type Coordinate (such as Latitude and Longitude), "
        "this number will be used as the default bin width (in degrees)."))
  :type    :double
  :default 10.0
  :audit   :getter)

(defsetting custom-formatting
  (deferred-tru "Object keyed by type, containing formatting settings")
  :encryption :no
  :type       :json
  :export?    true
  :default    {}
  :visibility :public
  :audit      :getter)

(defsetting show-homepage-data
  (deferred-tru
   (str "Whether or not to display data on the homepage. "
        "Admins might turn this off in order to direct users to better content than raw data"))
  :type       :boolean
  :default    true
  :visibility :authenticated
  :export?    true
  :audit      :getter)

(defsetting show-homepage-pin-message
  (deferred-tru
   (str "Whether or not to display a message about pinning dashboards. It will also be hidden if any dashboards are "
        "pinned. Admins might hide this to direct users to better content than raw data"))
  :type       :boolean
  :default    true
  :visibility :authenticated
  :export?    true
  :doc        false
  :audit      :getter)

(defsetting source-address-header
  (deferred-tru "Identify the source of HTTP requests by this header''s value, instead of its remote address.")
  :encryption :no
  :default "X-Forwarded-For"
  :export? true
  :audit   :getter
  :getter  (fn [] (some-> (setting/get-value-of-type :string :source-address-header)
                          u/lower-case-en)))

(defsetting not-behind-proxy
  (deferred-tru
   (str "Indicates whether Metabase is running behind a proxy that sets the source-address-header for incoming "
        "requests."))
  :type       :boolean
  :visibility :internal
  :default    false
  :export?    false)

(defsetting available-fonts
  "Available fonts"
  :visibility :public
  :export?    true
  :setter     :none
  :getter     u.fonts/available-fonts
  :doc        false)

(defsetting available-locales
  "Available i18n locales"
  :visibility :public
  :export?    true
  :setter     :none
  :getter     available-locales-with-names
  :doc        false)

(defsetting available-timezones
  "Available report timezone options"
  :visibility :public
  :export?    true
  :setter     :none
  :getter     (comp sort t/available-zone-ids)
  :doc        false)

(defsetting password-complexity
  "Current password complexity requirements"
  :visibility :public
  :setter     :none
  :getter     u.password/active-password-complexity)

(defsetting session-cookies
  (deferred-tru "When set, enforces the use of session cookies for all users which expire when the browser is closed.")
  :type       :boolean
  :visibility :public
  :default    nil
  :audit      :getter
  :doc "The user login session will always expire after the amount of time defined in MAX_SESSION_AGE (by default 2 weeks).
        This overrides the “Remember me” checkbox when logging in.
        Also see the Changing session expiration documentation page.")

(defsetting version
  "Metabase's version info"
  :visibility :public
  :setter     :none
  :getter     (constantly config/mb-version-info)
  :doc        false)

(defsetting token-features
  "Features registered for this instance's token"
  :visibility :public
  :setter     :none
  :getter     (fn [] {:advanced_permissions           (premium-features/enable-advanced-permissions?)
                      :attached_dwh                   (premium-features/has-attached-dwh?)
                      :audit_app                      (premium-features/enable-audit-app?)
                      :cache_granular_controls        (premium-features/enable-cache-granular-controls?)
                      :cache_preemptive               (premium-features/enable-preemptive-caching?)
                      :collection_cleanup             (premium-features/enable-collection-cleanup?)
                      :database_auth_providers        (premium-features/enable-database-auth-providers?)
                      :database_routing               (premium-features/enable-database-routing?)
                      :development-mode               (premium-features/development-mode?)
                      :config_text_file               (premium-features/enable-config-text-file?)
                      :content_verification           (premium-features/enable-content-verification?)
                      :dashboard_subscription_filters (premium-features/enable-dashboard-subscription-filters?)
                      :disable_password_login         (premium-features/can-disable-password-login?)
                      :email_allow_list               (premium-features/enable-email-allow-list?)
                      :email_restrict_recipients      (premium-features/enable-email-restrict-recipients?)
                      :embedding                      (premium-features/hide-embed-branding?)
                      :embedding_sdk                  (premium-features/enable-embedding-sdk-origins?)
                      :hosting                        (premium-features/is-hosted?)
                      :official_collections           (premium-features/enable-official-collections?)
                      :query_reference_validation     (premium-features/enable-query-reference-validation?)
                      :sandboxes                      (premium-features/enable-sandboxes?)
                      :scim                           (premium-features/enable-scim?)
                      :serialization                  (premium-features/enable-serialization?)
                      :session_timeout_config         (premium-features/enable-session-timeout-config?)
                      :snippet_collections            (premium-features/enable-snippet-collections?)
                      :sso_google                     (premium-features/enable-sso-google?)
                      :sso_jwt                        (premium-features/enable-sso-jwt?)
                      :sso_ldap                       (premium-features/enable-sso-ldap?)
                      :sso_saml                       (premium-features/enable-sso-saml?)
                      :upload_management              (premium-features/enable-upload-management?)
                      :whitelabel                     (premium-features/enable-whitelabeling?)
                      :llm_autodescription            (premium-features/enable-llm-autodescription?)})
  :doc        false)

(defsetting redirect-all-requests-to-https
  (deferred-tru "Force all traffic to use HTTPS via a redirect, if the site URL is HTTPS")
  :visibility :public
  :type       :boolean
  :default    false
  :audit      :getter
  :setter     (fn [new-value]
                ;; if we're trying to enable this setting, make sure `site-url` is actually an HTTPS URL.
                (when (if (string? new-value)
                        (setting/string->boolean new-value)
                        new-value)
                  (assert (some-> (site-url) (str/starts-with? "https:"))
                          (tru "Cannot redirect requests to HTTPS unless `site-url` is HTTPS.")))
                (setting/set-value-of-type! :boolean :redirect-all-requests-to-https new-value)))

(defsetting start-of-week
  (deferred-tru
   (str "This will affect things like grouping by week or filtering in GUI queries. "
        "It won''t affect most SQL queries, "
        "although it is used to set the WEEK_START session variable in Snowflake."))
  :visibility :public
  :export?    true
  :type       :keyword
  :default    :sunday
  :audit      :raw-value
  :getter     (fn []
                ;; if something invalid is somehow in the DB just fall back to Sunday
                (when-let [value (setting/get-value-of-type :keyword :start-of-week)]
                  (if (#{:monday :tuesday :wednesday :thursday :friday :saturday :sunday} value)
                    value
                    :sunday)))
  :setter      (fn [new-value]
                 (when new-value
                   (assert (#{:monday :tuesday :wednesday :thursday :friday :saturday :sunday} (keyword new-value))
                           (trs "Invalid day of week: {0}" (pr-str new-value))))
                 (setting/set-value-of-type! :keyword :start-of-week new-value)))

(defsetting cloud-gateway-ips
  (deferred-tru "Metabase Cloud gateway IP addresses, to configure connections to DBs behind firewalls")
  :visibility :public
  :type       :string
  :setter     :none
  :getter (fn []
            (when (premium-features/is-hosted?)
              (some-> (setting/get-value-of-type :string :cloud-gateway-ips)
                      (str/split #",")))))

(defsetting show-database-syncing-modal
  (deferred-tru
   (str "Whether an introductory modal should be shown after the next database connection is added. "
        "Defaults to false if any non-default database has already finished syncing for this instance."))
  :visibility :admin
  :type       :boolean
  :audit      :never
  :getter     (fn []
                (let [v (setting/get-value-of-type :boolean :show-database-syncing-modal)]
                  (if (nil? v)
                    (not (t2/exists? :model/Database
                                     :is_sample false
                                     :is_audit false
                                     :initial_sync_status "complete"))
                    ;; frontend should set this value to `true` after the modal has been shown once
                    v))))

(defsetting attachment-table-row-limit
  (deferred-tru "Maximum number of rows to render in an alert or subscription image.")
  :visibility :internal
  :type       :positive-integer
  :default    20
  :audit      :getter
  :getter     (fn []
                (let [value (setting/get-value-of-type :positive-integer :attachment-table-row-limit)]
                  (if-not (pos-int? value)
                    20
                    value)))
  :doc "Range: 1-100. To limit the total number of rows included in the file attachment
        for an email dashboard subscription, use MB_UNAGGREGATED_QUERY_ROW_LIMIT.")

;; This is used by the embedding homepage
(defsetting example-dashboard-id
  (deferred-tru "The ID of the example dashboard.")
  :visibility :authenticated
  :export?    false
  :type       :integer
  :setter     :none
  :getter     (fn []
                (let [id (setting/get-value-of-type :integer :example-dashboard-id)]
                  (when (and id (t2/exists? :model/Dashboard :id id :archived false))
                    id)))
  :doc        false)

(defsetting sql-parsing-enabled
  (deferred-tru "SQL Parsing is disabled")
  :visibility :internal
  :export?    false
  :default    true
  :type       :boolean)

(defsetting bug-reporting-enabled
  (deferred-tru "Enable bug report submissions.")
  :visibility :public
  :export?    false
  :type       :boolean
  :default    false
  :setter     :none
  :audit      :getter)

;;; !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
;;; !!                                                                                                !!
;;; !!                         DO NOT ADD ANY MORE SETTINGS IN THIS NAMESPACE                         !!
;;; !!                                                                                                !!
;;; !!   Please read https://metaboat.slack.com/archives/CKZEMT1MJ/p1738972144181069 for more info    !!
;;; !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
