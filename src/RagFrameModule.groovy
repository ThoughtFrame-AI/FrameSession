package assets.pdf;

import java.nio.file.Files
import java.util.regex.Matcher
import java.util.regex.Pattern

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.asset.Asset
import org.entermediadb.asset.MediaArchive
import org.entermediadb.asset.convert.ConvertResult
import org.entermediadb.scripts.ScriptLogger
import org.openedit.Data
import org.openedit.OpenEditException
import org.openedit.WebPageRequest
import org.openedit.data.Searcher
import org.openedit.hittracker.HitTracker
import org.openedit.hittracker.SearchQuery
import org.openedit.page.Page
import org.openedit.repository.ContentItem
import org.openedit.util.Exec
import org.openedit.util.ExecResult
import org.thoughtframe.llm.llama.LlamaManager
import org.thoughtframe.llm.llama.LlamaResponse
import org.thoughtframe.testbench.TestBench
import org.thoughtframe.testbench.modules.BaseTestingModule;

import groovy.json.JsonSlurper

public class RagFrameModule extends BaseTestingModule {

	public static Log log = LogFactory.getLog(RagFrameModule.class);
	//String model = "Qwen3-4B-Instruct-2507-Q4_K_M";
	//String model = "Qwen3-4B-Q4_K_M";
	//String model = "Qwen3-8B-Q4_K_M";
	String model = "Phi-4-mini-instruct-Q4_K_M"///.gguf

	public void pretrain(WebPageRequest inReq) throws Exception {
		ScriptLogger log = getScriptLogger(inReq);
		String assetid = inReq.findValue("assetid")
		String pagenum = inReq.findValue("pagenumber")
		if (!assetid || !pagenum) {
			throw new OpenEditException("Missing assetid or pagenumber")
		}

		MediaArchive archive = inReq.getPageValue("mediaarchive")
		TestBench bench = inReq.getPageValue("testbench")

		Searcher pdftext = archive.getSearcher("pdftext")
		LlamaManager manager =bench.getBean("llamaManager");//will have to be a per slot bean and lockable somehow

		///tring model = "Llama-3.2-1B-Instruct-Q4_K_M";
		//	String model = "Qwen3-4B-Q4_K_M";
		Data page = pdftext.query().exact("assetid", assetid).exact("pagenumber", pagenum).searchOne();
		if(page !=null) {
			def text = page.get("pagetext")
			log.info("Injesting : " + text)
			manager.injest(model, page.getId(), text)
			bench.fireFrameEvent(inReq, inReq.getUser(), "pagedone", null, "pdfpage")
		} else {
			bench.fireFrameEvent(inReq, inReq.getUser(), "pagemissing", null, "pdfpage")
		}
	}

	public void pretrainMultiPass(WebPageRequest inReq) throws Exception {
		ScriptLogger log = getScriptLogger(inReq);
		String assetid = inReq.findValue("assetid");
		if (assetid == null) {
			throw new OpenEditException("Missing assetid");
		}

		MediaArchive archive = inReq.getPageValue("mediaarchive");
		TestBench bench = inReq.getPageValue("testbench");
		Searcher pdftext = archive.getSearcher("pdftext");
		LlamaManager manager = bench.getBean("llamaManager");

		//String model = "Qwen3-4B-Q4_K_M";

		// get total page count
		SearchQuery q = pdftext.createSearchQuery();
		q.addMatches("assetid", assetid);
		HitTracker hits = pdftext.search(q);
		int totalPages = hits.size();
		log.info("Total pages found: " + totalPages);

		// --- PASS 1: coarse 4-page windows ---
		int windowSize = 10;
		for (int i = 2; i <= totalPages; i += windowSize) {
			StringBuilder combined = new StringBuilder();
			for (int j = i; j < i + windowSize && j <= totalPages; j++) {
				Data page = pdftext.query().exact("assetid", assetid).exact("pagenumber", String.valueOf(j)).searchOne();
				if (page != null) {
					combined.append(page.get("pagetext")).append("\n\n");
				}
			}
			if (combined.length() == 0) continue;

			String chunkId = assetid + "_block10_" + i;
			log.info("Pass1 ingest: " + chunkId);
			manager.injest(model, chunkId, combined.toString());

			// Optionally fire an event for UI / state tracking
			bench.fireFrameEvent(inReq, inReq.getUser(), "blockdone", null, "pdfpage");
		}

		windowSize = 5;
		for (int i = 1; i <= totalPages; i += windowSize) {
			StringBuilder combined = new StringBuilder();
			for (int j = i; j < i + windowSize && j <= totalPages; j++) {
				Data page = pdftext.query().exact("assetid", assetid).exact("pagenumber", String.valueOf(j)).searchOne();
				if (page != null) {
					combined.append(page.get("pagetext")).append("\n\n");
				}
			}
			if (combined.length() == 0) continue;

			String chunkId = assetid + "_block5_" + i;
			log.info("Pass1 ingest: " + chunkId);
			manager.injest(model, chunkId, combined.toString());

			// Optionally fire an event for UI / state tracking
			bench.fireFrameEvent(inReq, inReq.getUser(), "blockdone", null, "pdfpage");
		}



		//		// --- PASS 2: fine-grained 1-page verification ---
		//		for (int i = 1; i <= totalPages; i++) {
		//			Data page = pdftext.query().exact("assetid", assetid).exact("pagenumber", String.valueOf(i)).searchOne();
		//			if (page == null) continue;
		//
		//			String text = page.get("pagetext");
		//			if (text == null) continue;
		//
		//			String pageId = page.getId();
		//			log.info("Pass2 ingest: " + pageId);
		//			manager.injest(model, pageId, text);
		//			bench.fireFrameEvent(inReq, inReq.getUser(), "pagedone", null, "pdfpage");
		//		}

		log.info("Multi-pass pretrain completed for asset: " + assetid);
	}




	public void queryMultiPass(WebPageRequest inReq) throws Exception {
		ScriptLogger log = getScriptLogger(inReq);
		log.info("Start multi-pass hierarchical query (real offsets): " + new Date());

		String query = inReq.findValue("query");
		if (query == null || query.isEmpty()) {
			query = """
/no_think Rate from 0 to 100 how directly this content would help a student *find or explain* the correct answer to the question:
'Si un depósito tiene capacidad para 2000 litros y contiene 650 litros, ¿cuánto líquido falta para llenarlo completamente? (Opción correcta: 1350 litros)'

Give a high score only if the text explicitly teaches or demonstrates subtraction, volume/capacity, or a numeric example.

Respond ONLY with a number from 0 to 100.
""";
		}

		String assetid = inReq.findValue("assetid");
		if (assetid == null) throw new OpenEditException("Missing assetid");

		MediaArchive archive = (MediaArchive) inReq.getPageValue("mediaarchive");
		TestBench bench = (TestBench) inReq.getPageValue("testbench");
		LlamaManager manager = (LlamaManager) bench.getBean("llamaManager");
		Searcher pdftext = archive.getSearcher("pdftext");
		HitTracker allpages = pdftext.query().exact("assetid", assetid).sort("pagenumber").search();
		int total = allpages.size();

		Pattern scorePattern = Pattern.compile("(\\d{1,3})");
		List<Integer> hot10Blocks = new ArrayList<>();
		List<Integer> hot5Blocks = new ArrayList<>();

		int[] block10Starts = new int[] {
			1, 2, 12, 22, 32,  52, 62
		};
		int[] block5Starts  = new int[] {
			1, 6, 11, 16, 21, 26, 31, 36, 41, 46, 51, 56, 61
		};

//		// ───────── PASS 1: 10-page blocks ─────────
//		for (int start : block10Starts) {
//			if (start > total) break;
//			StringBuilder combined = new StringBuilder();
//			for (int j = start; j < start + 10 && j <= total; j++) {
//				Data page = (Data) allpages.get(j - 1);
//				String text = page.get("pagetext");
//				if (text != null && !text.isEmpty()) combined.append(text).append("\n\n");
//			}
//			if (combined.length() == 0) continue;
//
//			String block10Id = assetid + "_block10_" + start;
//			String blockText = combined.toString();
//			log.info("─────────────────────────────");
//			log.info("Querying 10-page block: " + block10Id);
//
//			try {
//				LlamaResponse resp = manager.querySession(model, block10Id, blockText, query);
//				String msg = resp.getMessage();
//				int score = 0;
//				Matcher m = scorePattern.matcher(msg);
//				if (m.find()) score = Integer.parseInt(m.group(1));
//				if (score >= 60) {
//					log.info("→ Block10 " + block10Id + " relevant (score " + score + ")");
//					hot10Blocks.add(start);
//				}
//			} catch (Exception e) {
//				log.error("Block10 failed: " + block10Id, e);
//			}
//		}

		// ───────── PASS 2: 5-page blocks inside hot 10s ─────────
		for (int start : block5Starts) {
			
			

			StringBuilder combined = new StringBuilder();
			for (int j = start; j < start + 5 && j <= total; j++) {
				Data page = (Data) allpages.get(j - 1);
				String text = page.get("pagetext");
				if (text != null && !text.isEmpty()) combined.append(text).append("\n\n");
			}
			if (combined.length() == 0) continue;

			String block5Id = assetid + "_block5_" + start;
			String blockText = combined.toString();
			log.info("Querying 5-page block: " + block5Id);

			try {
				LlamaResponse resp = manager.querySession(model, block5Id, blockText, query);
				String msg = resp.getMessage();
				int score = 0;
				Matcher m = scorePattern.matcher(msg);
				if (m.find()) score = Integer.parseInt(m.group(1));
				if (score >= 60) {
					log.info("→ Block5 " + block5Id + " relevant (score " + score + ")");
					hot5Blocks.add(start);
				}
			} catch (Exception e) {
				log.error("Block5 failed: " + block5Id, e);
			}
		}

		// ───────── PASS 3: page-level inside hot 5s ─────────
		for (int base : hot5Blocks) {
			for (int p = base; p < base + 5 && p <= total; p++) {
				Data page = (Data) allpages.get(p - 1);
				String pageId = page.getId();
				String text = page.get("pagetext");
				if (text == null || text.isEmpty()) continue;

				try {
					LlamaResponse resp = manager.querySession(model, pageId, text, query);
					String msg = resp.getMessage();
					int score = 0;
					Matcher m = scorePattern.matcher(msg);
					if (m.find()) score = Integer.parseInt(m.group(1));
					if (score >= 60) {
						log.info("★★ Highly Relevant Page " + page.get("pagenumber") + " (score " + score + ")" + text);
					}
				} catch (Exception e) {
					log.error("Page failed: " + pageId, e);
				}
			}
		}

		log.info("─────────────────────────────");
		log.info("Multi-pass hierarchical query complete for asset " + assetid);
		log.info("Total hot 10-blocks: " + hot10Blocks.size() + " | hot 5-blocks: " + hot5Blocks.size());
		log.info("Ended query: " + new Date());
	}



	public void extractWithDocling(WebPageRequest inReq) throws Exception {
		ScriptLogger log = getScriptLogger(inReq);
		String assetid = inReq.findValue("assetid")
		String pagenum = inReq.findValue("pagenumber")
		if (!assetid || !pagenum) {
			throw new OpenEditException("Missing assetid or pagenumber")
		}

		MediaArchive archive = inReq.getPageValue("mediaarchive")
		TestBench bench = inReq.getPageValue("testbench")
		Exec exec = (Exec) bench.getBean("exec")

		Asset asset = archive.getAsset(assetid)
		Searcher pdftext = archive.getSearcher("pdftext")

		// Original PDF
		ContentItem item = archive.getOriginalContent(asset)
		File pdfFile = new File(item.getAbsolutePath())
		int page = Integer.parseInt(pagenum)

		// Output dir: /WEB-INF/data/<catalog>/generated/<sourcepath>/page-<n>/
		File outDir = new File(
		archive.getRootDirectory(),
		"/WEB-INF/data/${archive.getCatalogId()}/generated/${asset.getSourcePath()}/page-${page}"
		)
		outDir.mkdirs()

		String mdPath = "/WEB-INF/data/" + archive.getCatalogId() + "/generated/" +
		asset.getSourcePath() + "/page-" + page + "/page-" + page + ".md";
		Page mdPage = archive.getPageManager().getPage(mdPath);
		if (mdPage.exists()) {
			log.info("Skipping page " + page + " for asset " + assetid + " — MD already exists: " + mdPath);
			return;
		}



		// 1) Split to single page with pdfseparate
		//    This writes page-<n>.pdf inside outDir
		String singleName = "page-${page}.pdf"
		File singlePagePdf = new File(outDir, singleName)

		// Only create if missing (cheap re-runs)
		if (!singlePagePdf.exists()) {
			List<String> sepArgs = [
				"-f",
				String.valueOf(page),
				"-l",
				String.valueOf(page),
				pdfFile.getAbsolutePath(),
				new File(outDir, "page-%d.pdf").getAbsolutePath()
			]
			ExecResult sepRes = exec.runExec("pdfseparate", sepArgs, true)
			if (!sepRes.isRunOk() || !singlePagePdf.exists()) {
				throw new OpenEditException("pdfseparate failed for page ${page}: ${sepRes?.standardError}")
			}
		}

		// 2) Run Docling on the single-page PDF
		List<String> dlArgs = [
			"--from",
			"pdf",
			"--to",
			"md",
			//	"--to", "json",
			"--tables",
			"--image-export-mode",
			"referenced",
			// images as files under *_artifacts
			"--page-batch-size",
			"1",
			"--output",
			outDir.getAbsolutePath(),
			singlePagePdf.getAbsolutePath()
		]
		ExecResult dlRes = exec.runExec("docling", dlArgs, true)
		if (!dlRes.isRunOk()) {
			throw new OpenEditException("Docling failed: ${dlRes.standardError}")
		}

		// 3) Read MD + JSON produced for the single page
		String base = singleName.replaceAll(/\.pdf$/,"")               // page-<n>
		File mdFile   = new File(outDir, "${base}.md")
		File jsonFile = new File(outDir, "${base}.json")
		String mdText = mdFile.exists() ? new String(Files.readAllBytes(mdFile.toPath()), "UTF-8") : null


		Map params = new HashMap()
		params.put("pagenum", page)
		ConvertResult result = archive.getTranscodeTools().createOutputIfNeeded(null, params, asset.getSourcePath(), "image800x800.webp")

		def root = null
		if (jsonFile.exists()) {
			root = new JsonSlurper().parse(jsonFile)
		}

		// 4) Collect artifact image filenames (no paths)
		List<String> imagenames = []
		File artifactsDir = new File(outDir, "${base}_artifacts")
		if (artifactsDir.exists() && artifactsDir.isDirectory()) {
			artifactsDir.listFiles({ f ->
				f.isFile() && f.name.toLowerCase().endsWith(".png")
			} as FileFilter)?.each { f ->
				imagenames << f.name  // filenames only
			}
		}

		// 5) Upsert the page record
		Data d = pdftext.query().exact("assetid", assetid).exact("pagenumber",pagenum).searchOne()
		if (d == null) {
			d = pdftext.createNewData()
			d.setValue("assetid", assetid)
			d.setValue("pagenumber", page)
		}

		if (mdText != null) {
			d.setValue("pagetext", mdText)
		}

		if (root != null) {
			int imgCount   = (root.pictures instanceof List) ? root.pictures.size() : 0
			int tableCount = (root.tables   instanceof List) ? root.tables.size()   : 0
			d.setValue("imagecount", imgCount)
			d.setValue("tablecount", tableCount)
		} else {
			d.setValue("imagecount", imagenames.size())
		}

		if (!imagenames.isEmpty()) {
			d.setValue("imagenames", imagenames)
		}

		d.setName("${asset.getName()} : ${page}")
		pdftext.saveData(d, null)

		// 6) Notify downstream
		Map event = [
			assetid   : assetid,
			pagenumber: page,
			outdir    : outDir.getAbsolutePath()
		]
		bench.fireFrameEvent(inReq, inReq.getUser(), "pagedone", null, "pdfpage", event)

		log.info("✅ Saved page ${page} for ${asset.getName()} (images=${imagenames.size()})")
	}

	public void testDeepQuery2pass(WebPageRequest inReq) throws Exception {
		ScriptLogger log = getScriptLogger(inReq);
		log.info("Start 2pass: " + new Date());

		String query = inReq.findValue("query");
		if (query == null || query.isEmpty()) {

			query = """
/no_think Rate  from 0 to 100 how directly this content would help a student *find or explain* the correct answer to the question:
'Si un depósito tiene capacidad para 2000 litros y contiene 650 litros, ¿cuánto líquido falta para llenarlo completamente? (Opción correcta: 1350 litros)'

Give a high score only if the text explicitly teaches or demonstrates subtraction, volume/capacity, or a numeric example.

Respond ONLY with a number from 0 to 100.

"""
		}

		String assetid = inReq.findValue("assetid");
		if (assetid == null) {
			throw new OpenEditException("Missing assetid");
		}


		MediaArchive archive = (MediaArchive) inReq.getPageValue("mediaarchive");
		Asset asset = archive.getAsset(assetid);
		TestBench bench = (TestBench) inReq.getPageValue("testbench");
		LlamaManager manager = (LlamaManager) bench.getBean("llamaManager");
		Searcher pdftext = archive.getSearcher("pdftext");

		HitTracker allpages = pdftext.query()
		.exact("assetid", assetid)
		.sort("pagenumber")
		.search();

		int total = allpages.size();
		int window = 4;
		log.info("Running deep query for " + total + " pages in windows of " + window);

		// --- STAGE 1: 4-page block queries ---
		for (int i = 0; i < total; i += window) {
			StringBuilder combined = new StringBuilder();
			List<Data> blockPages = new ArrayList<>();

			for (int j = i; j < i + window && j < total; j++) {
				Data page = (Data) allpages.get(j);
				String pagetext = page.get("pagetext");
				if (pagetext != null && !pagetext.isEmpty()) {
					combined.append(pagetext).append("\n\n");
					blockPages.add(page);
				}
			}
			if (combined.length() == 0) continue;

			String blockId = assetid + "_block_" + (i + 1);
			String blockText = combined.toString();

			// identical to ingestion
			LlamaResponse response = manager.querySession(model, blockId, blockText, query);
			String blockResult = response.getMessage();
			// log everything clearly
			log.info("──────────────────────────────────────────────");
			log.info("Block ID: " + blockId);
			log.info("Query: " + query);
			log.info("Full model reply:\n" + blockResult);
			log.info("──────────────────────────────────────────────");

			int score = 0;
			try {
				Matcher m = Pattern.compile("(\\d{1,2})").matcher(blockResult);
				if (m.find()) {
					score = Integer.parseInt(m.group(1));
				}
			} catch (Exception ignored) {}

			// run page-level if numeric ≥6 or a “yes” was returned
			if (score >= 6 ) {
				for (Data page : blockPages) {
					String pagetext = page.get("pagetext");
					if (pagetext == null || pagetext.isEmpty()) continue;

					String pageId = page.getId();
					LlamaResponse pageresp  = manager.querySession(model, pageId, pagetext, query);
					String pageResult = pageresp.getMessage();
					log.info("   → Page " + pageId + " reply:\n" + pageResult);
					log.info("------------------------------------------------");
					score = 0;
					try {
						Matcher m = Pattern.compile("(\\d{1,2})").matcher(pageResult);
						if (m.find()) {
							score = Integer.parseInt(m.group(1));
						}
					} catch (Exception ignored) {}
					if (score > 6) {
						log.info("Highly Revevant!" + page.get("pagenumber") );
					}
				}
			}
		}

		log.info("Deep query complete for asset " + assetid);
		log.info("ended 2pass: " + new Date());
	}





	public void testDeepQuery(WebPageRequest inReq) throws Exception {
		log.info("Start all pages: " + new Date());
		ScriptLogger log = getScriptLogger(inReq);

		String query = inReq.findValue("query");
		query = """
/no_think Rate from 0 to 100 how directly this content would help a student *find or explain* the correct answer to the question:
"In physics, how can we tell whether something is moving, and what is a reference frame?"

Give a high score only if the text explains that motion is defined relative to a reference frame, describes examples like a classroom, airplane, or rocket launch, or distinguishes position, distance, and displacement.
Give a low score if the content is about arithmetic, volume, or unrelated physical quantities.

Respond ONLY with a number from 0 to 100.


"""

	//query = "/no_think Provide a one word tag representing the overarching topic , in physics- for example, momentum.  If there isn't one just say none."
		String assetid = inReq.findValue("assetid");
		if (assetid == null) {
			throw new OpenEditException("Missing assetid");
		}

		MediaArchive archive = (MediaArchive) inReq.getPageValue("mediaarchive");
		TestBench bench = (TestBench) inReq.getPageValue("testbench");
		LlamaManager manager = (LlamaManager) bench.getBean("llamaManager");

		Searcher pdftext = archive.getSearcher("pdftext");
		HitTracker allpages = pdftext.query()
		.exact("assetid", assetid).sort("pagenumber")
		.search();

		//String model = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf";
		ArrayList scores = new ArrayList();
		for (Object o : allpages) {
			Data page = (Data) o;
			String pagetext = page.get("pagetext");
			if (pagetext == null || pagetext.isEmpty()) {
				continue;
			}

			// Page ID becomes the per-page session key
			String sessionId = page.getId();

			// Execute within the same model context (per slot)
			try {
				LlamaResponse pageresp  = manager.querySession(model, sessionId, pagetext, query);
				String pageResult = pageresp.getMessage();
				log.info("   → Page " + sessionId + " reply:\n" + pageResult);
				log.info("------------------------------------------------");
				int score = 0;
				try {
					Matcher m = Pattern.compile("(\\d{1,2})").matcher(pageResult);
					if (m.find()) {
						score = Integer.parseInt(m.group(1));
					}
					scores.add(score);
				} catch (Exception ignored) {
				scores.add("***");
					
					log.info("exception: " + ignored);
				}
				if (score > 80) {
					log.info("Highly Revevant!" + pagetext);
				}
			}catch(Exception e){
				scores.add("***");
				
				log.info("page failed");
			}

			log.info("Queried " + sessionId + " with model " + model);
		}
		log.info("Ended all pages: " + new Date());
		log.info(scores)
	}



	public void testFrameLoader(WebPageRequest inReq) {
		ScriptLogger log = getScriptLogger(inReq);
		log.info("yep runs ok");
	}
}
