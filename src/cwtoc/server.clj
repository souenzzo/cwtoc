(ns cwtoc.server
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [hiccup2.core :as h]
            [io.pedestal.http :as http]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [next.jdbc :as jdbc]
            [ring.middleware.session.store :as ss]
            [ring.util.mime-type :as mime])
  (:import (java.net URI)
           (java.sql Timestamp)
           (java.time Clock Instant)
           (java.util Properties)))

(set! *warn-on-reflection* true)


(defn form
  [{::csrf/keys [anti-forgery-token]} attr & bodies]
  (into [:form
         attr
         [:input {:hidden true
                  :name   csrf/anti-forgery-token-str
                  :value  anti-forgery-token}]]
    bodies))

(s/fdef form
  :args (s/cat :request (s/keys :req [::csrf/anti-forgery-token])
          :attr map?
          :&others (s/* any?)))


(defn profile-by-id
  [{::keys [cwtoc-conn]
    :keys  [path-params]}]
  (let [{:cara/keys [cargo datacriacao email golpes golpestomados id nivel reputacao respeito ultimaentrada votos]}
        (-> cwtoc-conn
          (jdbc/execute! ["SELECT * FROM cara WHERE cara.id = ?"
                          (parse-long (:id path-params))])
          first)]
    {:content [:div
               [:p "profile"]
               [:table
                [:thead]
                [:tbody
                 [:tr [:th "id"] [:td id]]
                 [:tr [:th "cargo"] [:td cargo]]
                 [:tr [:th "reputacao"] [:td reputacao]]
                 [:tr [:th "respeito"] [:td respeito]]
                 [:tr [:th "golpes"] [:td golpes]]
                 [:tr [:th "golpes tomados"] [:td golpestomados]]
                 [:tr [:th "nivel"] [:td nivel]]
                 [:tr [:th "golpes"] [:td golpes]]
                 [:tr [:th "email"] [:td email]]
                 [:tr [:th "votos"] [:td votos]]
                 [:tr [:th "ultima entrada"] [:td (str datacriacao)]]
                 [:tr [:th "data de crição"] [:td (str ultimaentrada)]]]]]}))

(defn profile
  [{:keys [session]
    :as   request}]
  (let [id (:cara/id session)]
    (when-not id
      (throw (ex-info (str "Only authed users are allowed to access profile page.")
               {:cognitect.anomalies/category :cognitect.anomalies/forbidden})))
    (profile-by-id (assoc request
                     :path-params {:id (str id)}))))

(defn login
  [{:keys [session query-params]
    :as   request}]
  (let [email (:cara/email session)
        {:keys [err-msg]} query-params]
    {:content [:div
               (when err-msg
                 [:aside
                  [:p err-msg]])
               (form request
                 {:action (route/url-for ::login!)
                  :method "POST"}
                 [:label
                  "email"
                  [:input {:name  "email"
                           :value email}]]
                 [:button {:type "submit"}
                  "login"])
               (when email
                 (form request
                   {:action (route/url-for ::login!)
                    :method "POST"}
                   [:button {:type "submit"}
                    (str "exit(" email ")")]))]}))

(defn login!
  [{::keys [cwtoc-conn clock]
    :keys  [session form-params]}]
  (let [{:keys [email]} form-params]
    (if email
      (jdbc/with-transaction [conn cwtoc-conn]
        (let [now (Timestamp/from (Instant/now clock))
              user-id (:cara/id (or (first (jdbc/execute! conn
                                             ["SELECT cara.id FROM cara WHERE cara.email = ?"
                                              email]))
                                  (and (jdbc/execute! conn
                                         ["INSERT INTO cara (email, dataCriacao, ultimaEntrada) VALUES (?, ?, ?)"
                                          email now now])
                                    (first (jdbc/execute! conn
                                             ["SELECT cara.id FROM cara WHERE cara.email = ?"
                                              email])))))]
          (jdbc/execute! conn
            ["UPDATE sessao SET autenticada = ? WHERE sessao.id = ?"
             user-id (:sessao/id session)])))
      (jdbc/execute! cwtoc-conn
        ["UPDATE sessao SET autenticada = ? WHERE sessao.id = ?"
         nil (:sessao/id session)]))
    {:headers {"Location" (if email
                            (route/url-for ::profile)
                            (route/url-for ::login))}
     :status  303}))

(defn partido!
  [{::keys [cwtoc-conn clock]
    :keys  [session form-params]}]
  (let [now (Timestamp/from (Instant/now clock))
        cara-id (:cara/id session)]
    (when-not cara-id
      (throw (ex-info "Você não pode criar um partido sem fazer login."
               {:cognitect.anomalies/category :cognitect.anomalies/forbidden})))
    (jdbc/with-transaction [conn cwtoc-conn]
      (jdbc/execute! conn
        ["INSERT INTO partido (nome, dataCriacao) VALUES (?, ?)"
         (:nome form-params)
         now])
      (jdbc/execute! conn
        ["INSERT INTO filiacao (partido, cara, dataCriacao) VALUES (?, ?, ?)"
         (-> (jdbc/execute! conn ["SELECT partido.id FROM partido WHERE nome = ?" (:nome form-params)])
           first
           :partido/id)
         cara-id
         now]))
    {:headers {"Location" (route/url-for ::partidos)}
     :status  303}))

(defn home
  [_]
  {:content [:p "home"]})

(defn partidos
  [{::keys [cwtoc-conn]
    :keys  [session]
    :as    request}]
  (let [partidos (jdbc/execute! cwtoc-conn
                   ["SELECT * FROM partido"])]
    {:content [:div
               [:p "partidos"]
               [:ul
                (for [{:partido/keys [nome id]} partidos]
                  [:li [:a {:href (route/url-for ::partido-by-id
                                    :params {:id id})}
                        nome]])]
               (when (:cara/id session)
                 (form request
                   {:action (route/url-for ::partido!)
                    :method "POST"}
                   [:label
                    "nome"
                    [:input {:name "nome"}]]
                   [:button {:type "submit"}
                    "fundar partido"]))]}))

(defn vender-voto
  [{::keys [cwtoc-conn]
    :keys  [session]}]
  (let [id (:cara/id session)]
    (when-not id
      (throw (ex-info "Only authed users are allowed to vender votos page."
               {:cognitect.anomalies/category :cognitect.anomalies/forbidden})))
    (jdbc/with-transaction [conn cwtoc-conn]
      (let [{:cara/keys [respeito reputacao]}
            (first (jdbc/execute! conn
                     ["SELECT * FROM cara WHERE cara.id = ?"
                      id]))]
        (jdbc/execute! conn
          ["UPDATE cara SET respeito = ?, reputacao = ? WHERE cara.id = ?"
           (inc (or respeito 0))
           (dec (or reputacao 0))
           id])))
    {:headers {"Location" (route/url-for ::camara)}
     :status  303}))

(defn camara
  [request]
  {:content [:ul
             [:li (form request
                    {:action (route/url-for ::vender-voto)
                     :method "POST"}
                    [:button {:type :submit}
                     "vender voto"])]]})

(defn partido-by-id
  [{::keys      [cwtoc-conn]
    :keys       [path-params session]
    :as         request}]
  (let [id (parse-long (:id path-params))
        {:partido/keys [nome]} (first (jdbc/execute! cwtoc-conn
                                        ["SELECT * FROM partido WHERE id = ?" id]))
        filiados (jdbc/execute! cwtoc-conn
                   ["SELECT * FROM cara
                     INNER JOIN filiacao on cara.id = filiacao.cara
                     INNER JOIN partido on (partido.id = ? AND filiacao.partido = partido.id)"
                    id])
        ids (set (map :cara/id filiados))]
    {:content [:div
               [:p (str "partido: " nome)]
               [:ul
                (for [{:cara/keys [id email]} filiados]
                  [:li [:a {:href (route/url-for ::profile-by-id
                                    :params {:id id})}
                        email]])]
               (cond
                 (contains? ids (:cara/id session))
                 (form request
                   {:action (route/url-for
                              ::desfiliar
                              :params {:id id})
                    :method "POST"}
                   [:input {:hidden true
                            :name   "partido"
                            :value  id}]
                   [:button {:type "submit"}
                    "desfiliar"])
                 (:cara/id session)
                 (form request
                   {:action (route/url-for
                              ::filiar
                              :params {:id id})
                    :method "POST"}
                   [:input {:hidden true
                            :name   "partido"
                            :value  id}]
                   [:button {:type "submit"}
                    "filiar"]))]}))



(defn filiar
  [{::keys [cwtoc-conn clock]
    :keys  [session form-params]}]
  (let [now (Timestamp/from (Instant/now clock))
        id (parse-long (:partido form-params))]
    (jdbc/execute! cwtoc-conn
      ["INSERT INTO filiacao (partido, cara, dataCriacao) VALUES (?, ?, ?)"
       (parse-long (:partido form-params))
       (:cara/id session)
       now])
    {:headers {"Location" (route/url-for ::partido-by-id
                            :params {:id id})}

     :status  303}))

(defn desfiliar
  [{::keys [cwtoc-conn]
    :keys  [session form-params]}]
  (let [id (parse-long (:partido form-params))]
    (jdbc/execute! cwtoc-conn
      ["DELETE FROM filiacao WHERE (filiacao.partido = ? AND filiacao.cara = ?)"
       id
       (:cara/id session)])
    {:headers {"Location" (route/url-for ::partido-by-id
                            :params {:id id})}
     :status  303}))

(defn jdbc-store
  [connectable clock]
  (let []
    (reify ss/SessionStore
      (read-session [_ key]
        (let [know-session (when (string? key)
                             (first (jdbc/execute! connectable
                                      ["SELECT sessao.id, sessao.csrf, sessao.autenticada, cara.email, cara.id
                                      FROM sessao
                                      LEFT JOIN cara on sessao.autenticada = cara.id
                                      WHERE sessao.id = ?"
                                       (parse-uuid key)])))]
          (when know-session
            (into {csrf/anti-forgery-token-str (:sessao/csrf know-session)}
              (remove (comp nil? val))
              know-session))))
      (write-session [_ key data]
        (let [key (or (some-> key parse-uuid)
                    (random-uuid))
              now (Timestamp/from (Instant/now clock))
              csrf (get data csrf/anti-forgery-token-str)]
          (jdbc/execute! connectable
            ["INSERT INTO sessao (id, csrf, dataCriacao) VALUES (?, ?, ?)"
             key csrf now])
          (str key))))))

(defn with-service-map
  [{::http/keys [interceptors]
    :as         service-map}]
  (assoc service-map
    ::http/interceptors (into [(interceptor/interceptor
                                 {:name  ::with-service-map
                                  :enter (fn [{:keys [request]
                                               :as   ctx}]
                                           (assoc ctx :request
                                             (merge service-map request)))})]
                          (concat
                            [(interceptor/interceptor {:nane  ::ex
                                                       :error (fn [ctx ex]
                                                                (let [cause (ex-cause ex)
                                                                      {:cognitect.anomalies/keys [category]} (ex-data cause)]
                                                                  (cond
                                                                    (contains? #{:cognitect.anomalies/forbidden}
                                                                      category) (assoc ctx :response
                                                                                  {:headers {"Location" (route/url-for ::login
                                                                                                          :params {:err-msg (ex-message cause)})}
                                                                                   :status  303})
                                                                    :else (throw cause))))})]
                            (butlast interceptors)
                            [(interceptor/interceptor
                               {:name  ::render
                                :leave (fn [{:keys [response]
                                             :as   ctx}]
                                         (if (contains? response :content)
                                           (let [html [:html
                                                       {:lang "pt-br"}
                                                       [:head
                                                        [:meta {:name    "description"
                                                                :content "cwtoc"}]
                                                        [:meta {:name    "theme-color"
                                                                :content "white"}]
                                                        [:link {:href "data:" :rel "icon"}]
                                                        [:meta {:name    "viewport"
                                                                :content "width=device-width, initial-scale=1.0"}]
                                                        [:title "cwtoc"]
                                                        #_[:link {:rel  "stylesheet"
                                                                  :href "/style.css"}]
                                                        [:link {:rel "stylesheet" :href "https://unpkg.com/mvp.css"}]
                                                        [:meta {:charset "utf-8"}]]
                                                       [:body
                                                        [:header
                                                         [:nav
                                                          [:ul
                                                           [:li [:a {:href (route/url-for ::login)} "login"]]
                                                           [:li [:a {:href (route/url-for ::home)} "home"]]
                                                           [:li [:a {:href (route/url-for ::camara)} "camara"]]
                                                           [:li [:a {:href (route/url-for ::partidos)} "partidos"]]
                                                           [:li [:a {:href (route/url-for ::profile)} "profile"]]]]]
                                                        [:main (:content response)]
                                                        [:footer
                                                         [:p (if-let [email (-> ctx :request :session :cara/email)]
                                                               (str "authed (" email ")")
                                                               "cwtoc")]]]]]
                                             (assoc ctx
                                               :response (merge {:status 200}
                                                           response
                                                           {:body    (->> html
                                                                       (h/html {:mode :html})
                                                                       (str "<!DOCTYPE html>\n"))
                                                            :headers (merge {"Content-Type" (mime/default-mime-types "html")}
                                                                       (:headers response))})))
                                           ctx))})]

                            [(last interceptors)]))))


(defn jdbc-url
  [database-url]
  (let [uri (URI/create database-url)
        user+password (some-> (.getUserInfo uri)
                        (string/split #":" 2))
        query (->> (some-> (.getQuery uri) vector)
                (concat (map (fn [[k v]] (str k "=" v))
                          (zipmap ["user" "password"]
                            user+password)))
                (string/join "&"))]
    (str (URI. "jdbc"
           (str (URI. "postgresql" nil
                  (.getHost uri) (.getPort uri) (.getPath uri)
                  query
                  (.getFragment uri)))
           nil))))

(defn create-service
  [{::keys []
    :as    service-map}]
  (let [cwtoc-jdbc-db-url (System/getProperty "cwtoc.server.cwtoc-db-url"
                            "postgres://postgres:postgres@localhost:5432/postgres")
        cwtoc-conn {:jdbcUrl (jdbc-url cwtoc-jdbc-db-url)}
        clock (Clock/systemUTC)]
    (-> service-map
      (assoc
        ::cwtoc-conn cwtoc-conn
        ::clock clock
        ::http/resource-path "public"
        ::http/routes (fn []
                        (route/expand-routes `#{["/" :get home]
                                                ["/login" :get login]
                                                ["/login" :post login!]
                                                ["/partidos" :get partidos]
                                                ["/partido" :post partido!]
                                                ["/camara" :get camara]
                                                ["/vender-voto" :post vender-voto]
                                                ["/partido/:id" :get partido-by-id]
                                                ["/partido/:id/filiar" :post filiar]
                                                ["/partido/:id/desfiliar" :post desfiliar]
                                                ["/profile" :get profile]
                                                ["/profile/:id" :get profile-by-id]}))
        ::http/enable-session {:store       (jdbc-store cwtoc-conn clock)
                               :cookie-name "cwtoc"}
        ::http/enable-csrf {})
      http/default-interceptors
      with-service-map)))

(defonce *http
  (atom nil))

(defn -main
  [& _args]
  ;; docker run --name my-postgres --env=POSTGRES_PASSWORD=postgres --rm -p 5432:5432 postgres:alpine
  (let [pom (some-> "META-INF/maven/cwtoc/server/pom.properties"
              io/resource
              io/input-stream)
        version (get (when pom (doto (Properties.)
                                 (.load pom)))
                  "version"
                  "develop")
        service-map {::http/port  (Long/getLong "cwtoc.server.http-port"
                                    8080)
                     ::http/type  :jetty
                     ::http/host  "0.0.0.0"
                     ::http/join? false}]
    (log/info :version version
      :service-map service-map)
    (swap! *http
      (fn [st]
        (some-> st http/stop)
        (-> service-map
          create-service
          http/create-server
          http/start)))
    (log/info :ok :ok)))

(comment
  (-main)
  ;; install schema
  (jdbc/execute! (::cwtoc-conn @*http)
    [(slurp (io/resource "schema.sql"))]))

