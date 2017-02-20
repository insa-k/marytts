/**
 * Copyright 2007 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 *
 * This file is part of MARY TTS.
 *
 * MARY TTS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package marytts.modules.nlp;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import marytts.modules.InternalModule;

import marytts.datatypes.MaryData;
import marytts.datatypes.MaryDataType;
import marytts.datatypes.MaryXML;
import marytts.server.MaryProperties;
import marytts.util.MaryUtils;
import marytts.util.dom.MaryDomUtils;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

import marytts.io.XMLSerializer;
import marytts.data.Utterance;
import marytts.data.Sequence;
import marytts.data.Relation;
import marytts.data.SupportedSequenceType;
import marytts.data.item.linguistic.Sentence;
import marytts.data.item.linguistic.Word;

import org.w3c.dom.Document;

/**
 * Part-of-speech tagger using OpenNLP.
 *
 * @author Marc Schr&ouml;der
 */

public class OpenNLPPosTagger extends InternalModule {
	private String propertyPrefix;
	private POSTaggerME tagger;
	private Map<String, String> posMapper = null;

	/**
	 * Constructor which can be directly called from init info in the config file. Different languages can call this code with
	 * different settings.
	 *
	 * @param locale
	 *            a locale string, e.g. "en"
	 * @param propertyPrefix
	 *            propertyPrefix
	 * @throws Exception
	 *             Exception
	 */
	public OpenNLPPosTagger(String locale, String propertyPrefix) throws Exception {
		super("OpenNLPPosTagger", MaryDataType.WORDS, MaryDataType.PARTSOFSPEECH, MaryUtils.string2locale(locale));
		if (!propertyPrefix.endsWith("."))
			propertyPrefix = propertyPrefix + ".";
		this.propertyPrefix = propertyPrefix;
	}

	public void startup() throws Exception {
		super.startup();

		InputStream modelStream = MaryProperties.needStream(propertyPrefix + "model");
		InputStream posMapperStream = MaryProperties.getStream(propertyPrefix + "posMap");

		tagger = new POSTaggerME(new POSModel(modelStream));
		modelStream.close();
		if (posMapperStream != null) {
			posMapper = new HashMap<String, String>();
			BufferedReader br = new BufferedReader(new InputStreamReader(posMapperStream, "UTF-8"));
			String line;
			while ((line = br.readLine()) != null) {
				// skip comments and empty lines
				if (line.startsWith("#") || line.trim().equals(""))
					continue;
				// Entry format: POS GPOS, i.e. two space-separated entries per line
				StringTokenizer st = new StringTokenizer(line);
				String pos = st.nextToken();
				String gpos = st.nextToken();
				posMapper.put(pos, gpos);
			}
			posMapperStream.close();
		}
	}

	@SuppressWarnings("unchecked")
	public MaryData process(MaryData d) throws Exception {
		Document doc = d.getDocument();
        XMLSerializer xml_ser = new XMLSerializer();
        Utterance utt = xml_ser.unpackDocument(doc);
        Relation rel_sent_word = utt.getRelation(SupportedSequenceType.SENTENCE,
                                                 SupportedSequenceType.WORD);

        int idx_sequence = 0;
        for (Sentence s: (Sequence<Sentence>) utt.getSequence(SupportedSequenceType.SENTENCE))
        {
            ArrayList<Word> words = (ArrayList<Word>) rel_sent_word.getRelatedItems(idx_sequence);

            // Generate the list of word in the sentence
            List<String> tokens = new ArrayList<String>();
            for (Word w: words)
            {
                tokens.add(w.getText());
            }

            // Trick the system in case of one ==> add a punctuation
            if (tokens.size() == 1)
                tokens.add(".");

            // POS Tagging
            List<String> partsOfSpeech = null;
            synchronized (this) {
                partsOfSpeech = tagger.tag(tokens);
            }

            // Associate POS to words
            Iterator<String> posIt = partsOfSpeech.iterator();
            for (Word w: words)
            {
                assert posIt.hasNext();
                String pos = posIt.next();

                if (w.getPOS() != null)
                    continue;

                if (posMapper != null) {
                    String gpos = posMapper.get(pos);
                    if (gpos == null)
                        logger.warn("POS map file incomplete: do not know how to map '" + pos + "'");
                    else
                        pos = gpos;
                }
                w.setPOS(pos);
            }

            idx_sequence++;
        }

        MaryData result = new MaryData(outputType(), d.getLocale());
        result.setDocument(xml_ser.generateDocument(utt));
        return result;
    }

}