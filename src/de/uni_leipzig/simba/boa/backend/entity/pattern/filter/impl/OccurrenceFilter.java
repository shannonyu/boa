package de.uni_leipzig.simba.boa.backend.entity.pattern.filter.impl;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import de.uni_leipzig.simba.boa.backend.configuration.NLPediaSettings;
import de.uni_leipzig.simba.boa.backend.entity.pattern.Pattern;
import de.uni_leipzig.simba.boa.backend.entity.pattern.filter.PatternFilter;
import de.uni_leipzig.simba.boa.backend.entity.patternmapping.PatternMapping;

/**
 * 
 * @author Daniel Gerber
 */
public class OccurrenceFilter implements PatternFilter {

//	private final NLPediaLogger logger = new NLPediaLogger(OccurrenceFilter.class);
	
	@Override
	public void filterPattern(PatternMapping patternMapping) {

		// collect all patterns which do not fit the filters, can't modify list while iteration
		Set<Pattern> correctPatterns = new HashSet<Pattern>();
		
		for ( Pattern p : patternMapping.getPatterns() ) {
			
			// skip this evaluation, because it was characterized as not suitable in a previous evaluation
			if ( p.isUseForPatternEvaluation() ) {
				
				// discard only patterns which might occur randomly
				p.setUseForPatternEvaluation(
						
						p.getNumberOfOccurrences() >= NLPediaSettings.getIntegerSetting("number.of.occurrence.threshold") 
				);
				
				int counter = 0;
				
				if ( p.isUseForPatternEvaluation() ) {
					
					// look if there are pairs available between the pattern occurs more than NUMBER_OF_UNIQUE_OCCURRENCES_THRESHOLD
					Map<String,Integer> learnedFrom = p.getLearnedFrom();
					for (Entry<String,Integer> entry : learnedFrom.entrySet()) {
						
						if ( entry.getValue() >= NLPediaSettings.getIntegerSetting("number.of.unique.occurrence.threshold") ) counter++;
					}
					
					if ( counter < 1 ) p.setUseForPatternEvaluation(false);
					
					if ( p.isUseForPatternEvaluation() ) {
						
						// look if there are more than NUMBER_OF_LEARNED_PAIRS pairs the pattern was learned from
						p.setUseForPatternEvaluation(
							
								learnedFrom.size() >= NLPediaSettings.getIntegerSetting("number.of.learned.pairs")
						);
					}
				}
			}
			// check after filter were applied if pattern is still suitable
			if ( p.isUseForPatternEvaluation() ) {
				
				correctPatterns.add(p);
			}
		}
		// replace them with the ones which survived the filtering
		patternMapping.setPatterns(correctPatterns);
	}
}
