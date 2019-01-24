/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package tracing.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;

import net.imagej.ImageJ;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import tracing.PathAndFillManager;
import tracing.SNT;
import tracing.SimpleNeuriteTracer;
import tracing.Tree;

/**
 * Importer for retrieving SWC data from neuromorpho.org.
 *
 * @author Tiago Ferreira
 */
public class NeuroMorphoLoader implements RemoteSWCLoader {

	private static final String BASE_URL = "http://neuromorpho.org/api/";
	private final static String NEURON_BASE_URL = BASE_URL + "neuron/name/";
	private String lastKnownStatus;

	private JSONObject getJSon(final String url, final String anchor) {
		Response response = null;
		final OkHttpClient client = new OkHttpClient();
		final Request request = new Request.Builder().url(url + anchor).build();
		String resStr = null;
		try {
			response = client.newCall(request).execute();
			if (!response.isSuccessful()) return null;
			resStr = response.body().string().toString();
		}
		catch (final IOException e) {
			SNT.error("Unexpected response from " + url + anchor, e);
		}
		finally {
			if (response != null) response.close();
			else return null;
		}
		return (resStr == null) ? null : new JSONObject(resStr);
	}

	/**
	 * Checks whether a connection to the NeuroMorpho database can be established.
	 *
	 * @return true, if an HHTP connection could be established, false otherwise
	 */
	@Override
	public boolean isDatabaseAvailable() {
		final JSONObject jObject = getJSon(BASE_URL, "health");
		if (jObject == null) return false;
		lastKnownStatus = (String) jObject.get("status");
		return "UP".equals(lastKnownStatus);
	}

	/**
	 * Gets the URL of the SWC file ('CNG version') associated with the specified
	 * cell ID.
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the reconstruction URL, or null if cell ID was not found or could
	 *         not be retrieved
	 */
	@Override
	public String getReconstructionURL(final String cellId) {
		final JSONObject json = getJSon(NEURON_BASE_URL, cellId);
		if (json == null) return null;
		final StringBuilder sb = new StringBuilder();
		sb.append("http://neuromorpho.org/dableFiles/");
		final String archive = (String) json.get("archive");
		sb.append(archive.toLowerCase().replaceAll(" ", "%20"));
		sb.append("/CNG%20version/");
		sb.append(cellId.replaceAll(" ", "%20"));
		sb.append(".CNG.swc");
		return sb.toString();
	}

	/**
	 * Gets the SWC data ('CNG version') associated with the specified cell ID as
	 * a reader
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the the character stream containing the data, or null if cell ID
	 *         was not found or could not be retrieved
	 */
	@Override
	public BufferedReader getReader(final String cellId) {
		try {
			final URL url = new URL(getReconstructionURL(cellId));
			final InputStream is = url.openStream();
			return new BufferedReader(new InputStreamReader(is));
		}
		catch (final IOException e) {
			return null;
		}
	}

	/**
	 * Gets the collection of Paths for the specified cell ID
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the data ('CNG version') for the specified cell as a {@link Tree},
	 *         or null if data could not be retrieved
	 */
	@Override
	public Tree getTree(final String cellId) {
		final String url = getReconstructionURL(cellId);
		if (url == null) return null;
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		if (pafm.importSWC(url)) {
			final Tree tree = new Tree(pafm.getPaths());
			tree.setLabel(cellId);
			return tree;
		}
		return null;
	}

	/*
	 * IDE debug method
	 *
	 * @throws IOException
	 */
	public static void main(final String... args) throws IOException {
		final String cellId = "cnic_002";
		final ImageJ ij = new ImageJ();
		final PathAndFillManager pafm = new PathAndFillManager();
		final NeuroMorphoLoader loader = new NeuroMorphoLoader();

		System.out.println("Neuromorpho available: " + loader
			.isDatabaseAvailable());
		System.out.println("# Getting neuron " + cellId);
		final String urlPath = loader.getReconstructionURL(cellId);
		System.out.println("URL :" + urlPath);
		pafm.importSWC(urlPath);
		final SimpleNeuriteTracer snt = new SimpleNeuriteTracer(ij.context(), pafm);
		snt.initialize(false, 1, 1);
		snt.startUI();
		System.out.println("# All done");
	}
}
