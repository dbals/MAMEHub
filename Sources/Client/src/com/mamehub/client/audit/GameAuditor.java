package com.mamehub.client.audit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mamehub.client.Utils;
import com.mamehub.thrift.FileInfo;
import com.mamehub.thrift.RomHashEntryValue;
import com.mamehub.thrift.RomInfo;

public class GameAuditor implements Runnable {
	final Logger logger = LoggerFactory.getLogger(GameAuditor.class);

	public static interface AuditHandler {
		public void auditFinished(GameAuditor gameAuditor);

		public void auditError(Exception e);

		public void updateAuditStatus(String string);
	}

	private AuditHandler handler;
	public ConcurrentMap<String, FileInfo> scanData = null;
	public static boolean abort = false;
	public boolean runScanner;
	public Thread gameAuditorThread;
	private HashScanner hashScanner;
	private ConcurrentMap<String, ArrayList<RomHashEntryValue>> hashEntryMap = null;
	private ConcurrentMap<String, String> chdMap = null;
	ConcurrentMap<String, RomInfo> mameRoms = getMameRomInfoMap();
	ConcurrentMap<String, RomInfo> messRoms = getMessRomInfoMap();
	private File indexDir;

	Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_40); // The standard
																	// analyzer
																	// does
																	// stemming,
																	// so don't
																	// use it.

	public GameAuditor(AuditHandler ah) throws IOException {
		GameAuditor.abort = false;
		this.handler = ah;

		indexDir = new File("AuditIndex" + Utils.AUDIT_DATABASE_VERSION);

		runScanner = true;
		scanData = Utils.getAuditDatabaseEngine().getOrCreateMap(
				FileInfo.class, "RomHash");
		hashEntryMap = Utils.getAuditDatabaseEngine().getOrCreatePrimitiveMap(
				"HashEntryMap2");
		chdMap = Utils.getAuditDatabaseEngine().getOrCreatePrimitiveMap(
				"ChdMap");

		if (!scanData.isEmpty() && !hashEntryMap.isEmpty() && indexDir.exists()) {
			runScanner = false;
		}

		startAudit(runScanner);
	}

	public boolean startAudit(boolean runScanner) {
		this.runScanner = runScanner;

		if (gameAuditorThread != null && gameAuditorThread.isAlive()) {
			// Cancel previous request
			cancelAudit();
		}
		GameAuditor.abort = false;

		gameAuditorThread = new Thread(this);
		gameAuditorThread.start();
		return true;
	}

	@Override
	public void run() {
		try {
			if (runScanner) {
				scan();
				audit();
				updateCache();
			}
		} catch (Exception e) {
			logger.error("Audit error!", e);
			GameAuditor.abort = true;
			Utils.deleteAuditDatabaseEngine();
			if (handler != null) {
				handler.auditError(new IOException(e));
			}
			return;
		}
		// Replace the existing db with the new one
		if (handler != null && !GameAuditor.abort) {
			handler.auditFinished(this);
		}
	}

	private void scan() throws Exception {
		List<String> systemNames = new ArrayList<String>();
		for(File softlist_file : new File("../hash").listFiles()) {
			systemNames.add(softlist_file.getName().split("\\.")[0]);
		}

		List<File> paths = new IniParser().getRomPaths();
		hashScanner = new HashScanner(handler, hashEntryMap, chdMap, systemNames);
		hashScanner.scan(paths);
		hashScanner = null;
		if (GameAuditor.abort) {
			return;
		}
	}

	private void audit() {
		try {
			logger.info("AUDIT: Creating in-memory hash entry map.");
			handler.updateAuditStatus("AUDIT: Creating in-memory hash entry map.");
			Map<String, ArrayList<RomHashEntryValue>> inMemoryHashEntryMap = new ConcurrentHashMap<String, ArrayList<RomHashEntryValue>>(
					hashEntryMap);
			handler.updateAuditStatus("AUDIT: Parsing MAME Roms");
			logger.info("Parsing MAME roms");
			new RomParser("../hash/mameROMs.xml.gz")
					.process(inMemoryHashEntryMap, chdMap, mameRoms, false,
							false, false);
			Utils.getAuditDatabaseEngine().commit();

			handler.updateAuditStatus("AUDIT: Parsing MESS Systems");
			// MESS Systems can't come from CHDs, so put a null here
			new RomParser("../hash/messROMs.xml.gz").process(
					inMemoryHashEntryMap, null, messRoms, true, true, true);
			Utils.getAuditDatabaseEngine().commit();

			final ExecutorService threadPool = Executors.newFixedThreadPool(8);
			Set<String> systemsToRemove = new HashSet<String>();
			String systems = "";
			Queue<Future<?>> futures = new LinkedList<Future<?>>();
			Map<String, ConcurrentMap<String, RomInfo>> systemRomMaps = new HashMap<String, ConcurrentMap<String, RomInfo>>();
			for (String system : messRoms.keySet()) {
				// Ensure that the maps exist before trying to create them in a
				// thread-unsafe environment
				systemRomMaps.put(system, getSystemRomInfoMap(system));
			}
			for (String system : messRoms.keySet()) {
				if (GameAuditor.abort) {
					return;
				}
				boolean missingSystem = false;
				if (messRoms.get(system).isSetMissingReason()) {
					missingSystem = true;
				}
				File file = new File("../hash/" + system + ".xml");
				if (file.exists()) {
					Map<String, RomInfo> systemCarts = systemRomMaps
							.get(system);
					systemCarts.clear();
					futures.add(threadPool.submit(new CartParser(system, file,
							inMemoryHashEntryMap, systemCarts, chdMap,
							missingSystem)));
					if (!systems.isEmpty()) {
						systems += ", ";
					}
					systems += system;
				} else {
					systemsToRemove.add(system);
				}
			}
			for (String systemToRemove : systemsToRemove) {
				messRoms.remove(systemToRemove);
			}
			while (!futures.isEmpty()) {
				handler.updateAuditStatus("AUDIT: Parsing carts for " + systems);
				futures.poll().get();
				if (!futures.isEmpty())
					systems = systems.substring(systems.indexOf(',') + 1);
			}
			threadPool.shutdown();
			if (!threadPool.awaitTermination(1, TimeUnit.HOURS)) {
				throw new IOException("Took too long to audit carts.");
			}
			Utils.getAuditDatabaseEngine().commit();

		} catch (Exception e) {
			logger.error("AUDIT EXCEPTION", e);
			// Delete the database just in case
			try {
				Utils.getAuditDatabaseEngine().close();
				Utils.wipeAuditDatabaseEngine();
				Utils.getAuditDatabaseEngine();
			} catch (IOException e1) {
				// We couldn't delete the database, this is probably bad but not
				// sure what else we can do at this point.
			}
			if (handler != null) {
				handler.auditError(new IOException(e));
			}
			return;
		}
	}

	private void updateCache() throws IOException {
		handler.updateAuditStatus("AUDIT: Deleting old index");
		FileUtils.deleteDirectory(indexDir);
		indexDir.mkdir();
		handler.updateAuditStatus("AUDIT: Creating new index");
		Directory dir = FSDirectory.open(indexDir);
		IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_40,
				analyzer);

		// Create a new index in the directory, removing any
		// previously indexed documents:
		iwc.setOpenMode(OpenMode.CREATE);

		// Optional: for better indexing performance, if you
		// are indexing many documents, increase the RAM
		// buffer. But if you do this, increase the max heap
		// size to the JVM (eg add -Xmx512m or -Xmx1g):
		//
		// iwc.setRAMBufferSizeMB(256.0);

		IndexWriter writer = new IndexWriter(dir, iwc);

		{
			handler.updateAuditStatus("AUDIT: Indexing Arcade roms");
			for (RomInfo ri : mameRoms.values()) {
				addIndexEntry(writer, "Arcade", ri.description, ri.id);
			}
		}

		for (String system : messRoms.keySet()) {
			handler.updateAuditStatus("AUDIT: Indexing " + system);
			Map<String, RomInfo> systemCarts = getSystemRomInfoMap(system);
			for (RomInfo ri : systemCarts.values()) {
				addIndexEntry(writer, system, ri.description, ri.id);
			}
		}

		handler.updateAuditStatus("AUDIT: Merging index.");

		// NOTE: if you want to maximize search performance,
		// you can optionally call forceMerge here. This can be
		// a terribly costly operation, so generally it's only
		// worth it when your index is relatively static (ie
		// you're done adding documents to it):
		//
		writer.forceMerge(1);

		handler.updateAuditStatus("AUDIT: Closing index.");
		writer.close();
	}

	private void addIndexEntry(IndexWriter writer, String system,
			String description, String shortName) throws IOException {
		Document doc = new Document();

		doc.add(new StringField("Machine", system, Field.Store.YES));
		doc.add(new StringField("gameDescription", description, Field.Store.YES));
		doc.add(new TextField("gameDescriptionLowerCase", description
				.toLowerCase().replace("'", ""), Field.Store.YES));
		doc.add(new StringField("gameName", shortName, Field.Store.YES));

		writer.updateDocument(new Term("gameName", shortName), doc);
	}

	public String getSystemForRom(String romId) {
		for (Map.Entry<String, RomInfo> entry : mameRoms.entrySet()) {
			if (entry.getKey().equals(romId)) {
				return "Arcade";
			}
		}

		for (String system : messRoms.keySet()) {
			Map<String, RomInfo> systemCarts = getSystemRomInfoMap(system);
			for (Map.Entry<String, RomInfo> entry : systemCarts.entrySet()) {
				if (entry.getKey().equals(romId)) {
					return system;
				}
			}
		}

		return null;
	}

	public ConcurrentMap<String, RomInfo> getSystemRomInfoMap(String system) {
		return Utils.getAuditDatabaseEngine().getOrCreateMap(RomInfo.class,
				system + "Roms");
	}

	public ConcurrentMap<String, RomInfo> getMameRomInfoMap() {
		return Utils.getAuditDatabaseEngine().getOrCreateMap(RomInfo.class,
				"MAMERoms");
	}

	public ConcurrentMap<String, RomInfo> getMessRomInfoMap() {
		return Utils.getAuditDatabaseEngine().getOrCreateMap(RomInfo.class,
				"MESSRoms");
	}

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		GameAuditor gameAuditor = new GameAuditor(null);
		new Thread(gameAuditor).start();
	}

	public boolean isAuditing() {
		// If there is no thread, the thread is dead, or we are scanning hashes,
		// then we are NOT auditing
		return !(gameAuditorThread == null
				|| gameAuditorThread.isAlive() == false || hashScanner != null);
	}

	public void cancelAudit() {
		GameAuditor.abort = true;
		while (gameAuditorThread != null && gameAuditorThread.isAlive()) {
			try {
				gameAuditorThread.join();
			} catch (InterruptedException e) {
			}
		}
		GameAuditor.abort = false;
	}

	public static class RomQueryResult {
		public float score;
		public RomInfo romInfo;

		public RomQueryResult(float score, RomInfo romInfo) {
			this.score = score;
			this.romInfo = romInfo;
		}
	}

	public List<RomQueryResult> queryRoms(String inputQuery,
			Map<String, Set<String>> cloudRoms) {
		if (isAuditing()) {
			return new ArrayList<RomQueryResult>();
		}

		IndexReader reader = null;
		try {
			Directory dir = FSDirectory.open(indexDir);
			reader = DirectoryReader.open(dir);
			IndexSearcher searcher = new IndexSearcher(reader);

			QueryParser parser = new QueryParser(Version.LUCENE_40,
					"gameDescriptionLowerCase", analyzer);

			List<RomQueryResult> queryResult = new ArrayList<RomQueryResult>();

			// Do some initial subs
			inputQuery = inputQuery.trim().replaceAll("\\s+", " ");
			if (!inputQuery.endsWith("\"")) {
				inputQuery += "*";
			}

			// Convert to lower case
			inputQuery = inputQuery.toLowerCase();

			// Escape special characters: + - && || ! ( ) { } [ ] ^ " ~ * ? : \
			inputQuery = inputQuery.replace("\\", "\\\\").replace(":", "\\:");

			// If the person put "machine:", actually put the colon back in and
			// capitalize it
			inputQuery = inputQuery.replace("machine\\:", "Machine:");

			// Remove periods and apostrophes
			inputQuery = inputQuery.replace("'", "").replace(".", "");
			logger.info("Starting query: " + inputQuery);

			Query query;
			try {
				query = parser.parse(inputQuery);
			} catch (ParseException ex) {
				// TODO: Try to replace more special characters and requery
				return queryResult;
			}
			TopDocs results = searcher.search(query, 10000);
			// System.out.println("GOT " + results.scoreDocs.length +
			// " RESULTS");
			for (ScoreDoc scoreDoc : results.scoreDocs) {
				// System.out.println(scoreDoc.score + ": " +
				// searcher.doc(scoreDoc.doc).getField("gameDescription").stringValue());
				// System.out.println(searcher.doc(scoreDoc.doc).getField("Machine").stringValue());
				// System.out.println(searcher.doc(scoreDoc.doc).getField("gameName").stringValue());
				String system = searcher.doc(scoreDoc.doc).getField("Machine")
						.stringValue();
				String romid = searcher.doc(scoreDoc.doc).getField("gameName")
						.stringValue();
				RomInfo romInfo = null;
				if (system.toLowerCase().equals("arcade")) {
					romInfo = getMameRomInfoMap().get(romid);
				} else {
					romInfo = getSystemRomInfoMap(system).get(romid);
					}
					// System.out.println(system + " : " + romid + " : " + romInfo);
					if (romInfo.missingReason != null
							&& (cloudRoms == null
									|| !cloudRoms.containsKey(romInfo.system) || !cloudRoms
								.get(romInfo.system).contains(romInfo.id))) {
						continue;
					}
					// System.out.println("GOT ROM: " + romInfo);
					queryResult.add(new RomQueryResult(scoreDoc.score, romInfo));
					if (queryResult.size() >= 1000) {
						break;
				}
			}
			logger.info("Finished query");

			/*
			 * query = query.toLowerCase(); // System.out.println("CHECKING " +
			 * system + " " + query); for (Map.Entry<String, TreeMap<String,
			 * String>> parentEntry : romSystemNameIdMap .entrySet()) { for
			 * (Map.Entry<String, String> entry : parentEntry.getValue()
			 * .entrySet()) { // NOTE: Lower score is better int score =
			 * StringUtils.getLevenshteinDistance(query, entry
			 * .getKey().toLowerCase()) - (entry.getKey().length()); if
			 * (entry.getKey().toLowerCase().contains(query)) { score -= 1e6; }
			 * if (entry.getKey().toLowerCase().startsWith(query)) { score -=
			 * 1e6; } // System.out.println("ID " + entry.getValue() + " NAME "
			 * + // entry.getKey()); queryResult.add(new RomQueryResult(score,
			 * entry.getValue(), entry.getKey())); //
			 * System.out.println(queryResult.size()); } }
			 */

			return queryResult;
		} catch (IOException e) {
			logger.error("Query Roms error", e);
			throw new RuntimeException(e);
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}
}
