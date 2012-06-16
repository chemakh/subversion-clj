;; ## Read-only Subversion access
;;
;; This code is extracted from <a href="http://beanstalkapp.com">beanstalkapp.com</a> caching daemon[1].
;;
;; Right now this is just a read-only wrapper around Java's SVNKit that allows you to look
;; into contents of local and remote repositories (no working copy needed). 
;; 
;; At this moment all this library can do is get unified information about all revisions or some particular revision
;; in the repo. However I'm planning to extend this code as Beanstalk uses more Clojure code
;; for performance critical parts
;;
;; [1] <a href="http://blog.beanstalkapp.com/post/23998022427/beanstalk-clojure-love-and-20x-better-performance">Post in Beanstalk's blog about this</a>
;;

(ns subversion-clj.core
  (:require 
    [clojure.string :as string])
  (:import 
     [org.tmatesoft.svn.core.internal.io.fs FSRepositoryFactory FSPathChange]
     [org.tmatesoft.svn.core.internal.io.dav DAVRepositoryFactory]
     [org.tmatesoft.svn.core.internal.io.svn SVNRepositoryFactoryImpl]
     [org.tmatesoft.svn.core.internal.util SVNHashMap SVNHashMap$TableEntry]     
     [org.tmatesoft.svn.core SVNURL SVNLogEntry SVNLogEntryPath SVNException]
     [org.tmatesoft.svn.core.io SVNRepository SVNRepositoryFactory]
     [org.tmatesoft.svn.core.wc SVNWCUtil]
     [java.io File]
     [java.util.LinkedList]))

(declare log-record node-kind node-kind-at-rev)

(DAVRepositoryFactory/setup)
(SVNRepositoryFactoryImpl/setup)
(FSRepositoryFactory/setup)

(defn repo-for
  "Creates an instance of SVNRepository subclass from a legitimate Subversion URL like:
  
  * `https://wildbit.svn.beanstalkapp.com/somerepo`
  * `file:///storage/somerepo`
  * `svn://internal-server:3122/somerepo`

  You can use it like:

        (repo-for \"file:///storage/my-repo\")

  Or like this:

        (repo-for 
          \"https://wildbit.svn.beanstalkapp.com/repo\" 
          \"login\" 
          \"pass\")"
  (^SVNRepository [repo-path]
    (SVNRepositoryFactory/create (SVNURL/parseURIEncoded repo-path)))
  
  (^SVNRepository [repo-path name password]
    (let [repo (repo-for repo-path)
          auth-mgr (SVNWCUtil/createDefaultAuthenticationManager name password)]
      (.setAuthenticationManager repo auth-mgr)
      repo)))

(defn revisions-for 
  "Returns an array with all the revision records in the repository."
  [repo]
  (->> (.log repo (into-array String []) ^LinkedList (java.util.LinkedList.) 1 -1 true false)
    (map (partial log-record repo))
    (into [])))

(defn revision-for 
  "Returns an individual revision record.

   Example record for a copied directory:

        {:revision 6
        :author \"railsmonk\"
        :message \"copied directory\"
        :changes [[\"dir\" [\"new-dir\" \"old-dir\" 5] :copy]]}

   Example record for an edited files:

        {:revision 11
        :author \"railsmonk\"
        :message \"editing files\"
        :changes [[\"file\" \"commit1\" :edit]
                  [\"file\" \"commit3\" :edit]]}"
  [repo revision]
  (let [revision (Long. revision)]
    (log-record repo (first (.log repo (into-array String []) ^LinkedList (java.util.LinkedList.) revision revision true false)))))

(defn node-kind 
  "Returns kind of a node path at certain revision - file or directory."
  [repo path rev]
  (let [basename (.getName (File. ^String path))]
    (if (>= (.indexOf basename ".") 0)
      "file"
      (let [node-kind-at-current-rev (node-kind-at-rev repo path rev)]
        (if (= "none" node-kind-at-current-rev)
          (node-kind-at-rev repo path (- rev 1))
          node-kind-at-current-rev)))))

(defn- node-kind-at-rev ^String [^SVNRepository repo ^String path ^Long rev]
  (.toString (.checkPath repo path rev)))

(defn- normalize-path [path]
  (if (= path "/")
    "/"
    (if (= (first path) \/)
      (apply str (rest path))
      path)))

(defn- change-kind [change-rec] ;  FSPathChange
  (let [change (if (instance? FSPathChange change-rec) 
                 (.. change-rec getChangeKind toString)
                 (.. change-rec getType toString))
        copy-path (.getCopyPath change-rec)]
    (cond
      copy-path :copy
      (= change "add") :add
      (= change "A") :add
      (= change "modify") :edit
      (= change "M") :edit
      (= change "delete") :delete
      (= change "D") :delete
      (= change "replace") :replace
      (= change "R") :replace
      (= change "reset") :reset)))

(defn- detailed-path [repo rev log-record ^SVNHashMap$TableEntry path-record]
  (let [path ^String (normalize-path (.getKey path-record))
        change-rec ^FSPathChange (.getValue path-record)
        node-kind (node-kind repo path rev)
        change-kind (change-kind change-rec)]
    (if (= change-kind :copy)
      [node-kind [path 
                  (normalize-path (.getCopyPath change-rec)) 
                  (.getCopyRevision change-rec)]
       change-kind]
      [node-kind path change-kind])))

(defn- changed-paths [repo rev ^SVNLogEntry log-record]
  (if (= (str rev) "0")
    []
    (map #(detailed-path repo rev log-record %) ^SVNHashMap (.getChangedPaths log-record))))

(defn- log-record [repo ^SVNLogEntry log-obj]
  (let [rev (.getRevision log-obj)
        author (.getAuthor log-obj)
        date (.getDate log-obj)
        message (.getMessage log-obj)
        paths (doall (changed-paths repo rev log-obj))]
    {:revision rev
     :author author
     :time date
     :message (string/trim (str message))
     :changes paths}))