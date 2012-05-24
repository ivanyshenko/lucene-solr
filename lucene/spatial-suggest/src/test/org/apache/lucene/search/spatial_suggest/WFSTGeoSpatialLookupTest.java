package org.apache.lucene.search.spatial_suggest;



import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;


import org.apache.lucene.search.spell.TermFreqIterator;
import org.apache.lucene.search.suggest.Lookup.LookupResult;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util._TestUtil;
import org.junit.Test;
import org.apache.lucene.search.spatial_suggest.WFSTGeoSpatialLookup;

import com.spatial4j.core.shape.simple.RectangleImpl;

public class WFSTGeoSpatialLookupTest extends LuceneTestCase {

  @Test
  public void testWFSTGeoSpatialLookupTest() throws FileNotFoundException, IOException {
    // the locations of the first 4 businesses are in Alaska
    // the score for all of them have been randomly picked
    TermFreq keys[] = new TermFreq[] {
        new TermFreq("men & women ctr|Men & Women Ctr|-122.420558|37.805775", 865),
        new TermFreq("mo|Mo|-122.420558|37.805775", 1),
        new TermFreq("moondog visions|Moondog Visions|-122.418358326|37.7494333411", 358),
        new TermFreq("molarbytes|Molarbytes|-122.4634694|37.7640745", 691),
        new TermFreq("m biz in berkeley|M Biz In Berkeley|-122.2907133|37.8478262", 847)
      };

    WFSTGeoSpatialLookup lookup = new WFSTGeoSpatialLookup(false, 4, 5);
    lookup.build(new TermFreqArrayIterator(keys));

    // build a Rectangle covering the first 4 elements from keys
    RectangleImpl rect = new RectangleImpl(-122.52159118652344, -122.31697082519531,
        37.693601037244406, 37.856422880849514);

    List<LookupResult> lookupResults = lookup.lookup("m", rect, 3);
    assertEquals(3, lookupResults.size());
    assertEquals("Moondog Visions", lookupResults.get(2).key);

    lookupResults = lookup.lookup("m", rect, 2);
    assertEquals(2, lookupResults.size());
    assertEquals("Molarbytes", lookupResults.get(1).key);

    lookupResults = lookup.lookup("m", rect, 1);
    assertEquals(1, lookupResults.size());
    assertEquals("Men & Women Ctr", lookupResults.get(0).key);

    lookupResults = lookup.lookup("molar", rect, 1);
    assertEquals(1, lookupResults.size());
    assertEquals("Molarbytes", lookupResults.get(0).key);

    lookupResults = lookup.lookup("m", rect, 4);
    assertEquals(4, lookupResults.size());
  }

  @Test
  public void testDuplicateBusinesses() throws FileNotFoundException, IOException {
    // Duplicate businesses ie same lookup, display, latitude, longitude but different scores
    // should only result in the higher scoring business being suggested
    TermFreq keys[] = new TermFreq[] {
        new TermFreq("men & women ctr|Men & Women Ctr|-122.420558|37.805775", 865),
        new TermFreq("men & women ctr|Men & Women Ctr|-122.420558|37.805775", 86),
        new TermFreq("mo|Mo|-122.420558|37.805775", 1),
        new TermFreq("moondog visions|Moondog Visions|-122.418358326|37.7494333411", 358)
    };

    WFSTGeoSpatialLookup lookup = new WFSTGeoSpatialLookup(false, 4, 5);
    lookup.build(new TermFreqArrayIterator(keys));

    // build a Rectangle covering the first 4 elements from keys
    RectangleImpl rect = new RectangleImpl(-122.52159118652344, -122.31697082519531,
        37.693601037244406, 37.856422880849514);

    List<LookupResult> lookupResults = lookup.lookup("m", rect, 3);
    assertEquals(3, lookupResults.size());
    assertEquals(865, lookupResults.get(0).value);
    assertEquals("Men & Women Ctr", lookupResults.get(0).key);
    assertEquals("Moondog Visions", lookupResults.get(1).key);
    assertEquals("Mo", lookupResults.get(2).key);
  }

  @Test
  public void testBusinessesAroundPrimeMeridian() throws FileNotFoundException, IOException {
    // Locations like the prime meridian result in geohashes that do not share a common
    // prefix. Since the matching algorithm does not rely on any properties of geohashes
    // involving proximity this should not be an issue
    TermFreq keys[] = new TermFreq[] {
        new TermFreq("men & women ctr|Men & Women Ctr|-0.420558|37.805775", 865),
        new TermFreq("mo|Mo|+0.205581|37.805775", 1),
        new TermFreq("moondog visions|Moondog Visions|-0.418358326|37.7494333411", 358)
    };

    WFSTGeoSpatialLookup lookup = new WFSTGeoSpatialLookup(false, 4, 5);
    lookup.build(new TermFreqArrayIterator(keys));

    // build a Rectangle covering all the elements from keys
    RectangleImpl rect = new RectangleImpl(-0.52159118652344, +0.31697082519531,
        37.693601037244406, 37.856422880849514);

    List<LookupResult> lookupResults = lookup.lookup("m", rect, 3);
    assertEquals(3, lookupResults.size());
    assertEquals("Men & Women Ctr", lookupResults.get(0).key);
    assertEquals("Moondog Visions", lookupResults.get(1).key);
    assertEquals("Mo", lookupResults.get(2).key);
  }


  class SuggestOutput {
    public SuggestOutput(String display, long weight) {
      this.display = display;
      this.weight = weight;
    }
    public String display;
    public long weight;
  }


  /**
   * The following test copies and then modifies WFSTCompletionLookup
   * to deal with geospatial suggestions.
   * @throws Exception
   */
  @Test
  public void testRandom() throws Exception {
    int numWords = atLeast(1000);

    final TreeMap<String,SuggestOutput> slowCompletor = new TreeMap<String,SuggestOutput>();
    final TreeSet<String> allPrefixes = new TreeSet<String>();

    TermFreq[] keys = new TermFreq[numWords];


    // Mountain View bounding geobox
    double min_longitude = -122.1478808;
    double max_longitude = -122.0198214;
    double min_latitude = 37.350580399999998;
    double max_latitude = 37.421506200000003;

    RectangleImpl rect = new RectangleImpl(min_longitude, max_longitude,
        min_latitude, max_latitude);

    double height = rect.getHeight();
    double width = rect.getWidth();

    for (int i = 0; i < numWords; i++) {
      String lookupString;
      while (true) {
        // TODO: would be nice to fix this slowCompletor/comparator to
        // use full range, but we might lose some coverage too...
        lookupString = _TestUtil.randomSimpleString(random());
        if (!slowCompletor.containsKey(lookupString)) {
          break;
        }
      }

      for (int j = 1; j < lookupString.length(); j++) {
        allPrefixes.add(lookupString.substring(0, j));
      }

      // we can probably do Integer.MAX_VALUE here, but why worry.
      int weight = random().nextInt(1<<24);
      slowCompletor.put(lookupString, new SuggestOutput(lookupString, (long)weight));

      // generate latitude, longitude in the Mountain View bounding box
      double latitude = min_latitude + (random().nextFloat() * height);
      double longitude = min_longitude + (random().nextFloat() * width);

      // display string and lookup string are the same here
      String input = lookupString + "|" + lookupString + "|" + longitude + "|" + latitude;
      keys[i] = new TermFreq(input, weight);
    }

    WFSTGeoSpatialLookup suggester = new WFSTGeoSpatialLookup(false, 4, 5);
    suggester.build(new TermFreqArrayIterator(keys));

    Random random = new Random(random().nextLong());
    for (String prefix : allPrefixes) {
      final int topN = _TestUtil.nextInt(random, 1, 10);
      List<LookupResult> r = suggester.lookup(_TestUtil.stringToCharSequence(prefix, random), rect, topN);

      // 2. go thru whole treemap (slowCompletor) and check its actually the best suggestion
      final List<LookupResult> matches = new ArrayList<LookupResult>();

      // TODO: could be faster... but its slowCompletor for a reason
      for (Map.Entry<String,SuggestOutput> e : slowCompletor.entrySet()) {
        if (e.getKey().startsWith(prefix)) {
          matches.add(new LookupResult(e.getValue().display, e.getValue().weight));
        }
      }

      assertTrue(matches.size() > 0);

      // Sort the matches from the slowCompletor using the same
      // criteria used by GeoSpatialSuggest
      Collections.sort(matches, new Comparator<LookupResult>() {
        public int compare(LookupResult left, LookupResult right) {
          int cmp = Float.compare(right.value, left.value);
          if (cmp == 0) {
            return left.compareTo(right);
          } else {
            return cmp;
          }
        }
      });

      if (matches.size() > topN) {
        matches.subList(topN, matches.size()).clear();
      }

      assertEquals(matches.size(), r.size());

      for(int hit=0;hit<r.size();hit++) {
        //System.out.println("  check hit " + hit);
        assertEquals(matches.get(hit).key.toString(), r.get(hit).key.toString());
        assertEquals(matches.get(hit).value, r.get(hit).value, 0f);
      }
    }
  }
}