package com.ibm.drl.hbcp.core.attributes.normalization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.ibm.drl.hbcp.core.attributes.ArmifiedAttributeValuePair;
import com.ibm.drl.hbcp.core.attributes.Attribute;
import com.ibm.drl.hbcp.core.attributes.AttributeType;
import com.ibm.drl.hbcp.core.attributes.normalization.normalizers.BinaryAttributeNormalizer;
import com.ibm.drl.hbcp.core.attributes.normalization.normalizers.MixedGenderNormalizer;
import com.ibm.drl.hbcp.core.attributes.normalization.normalizers.Normalizer;
import com.ibm.drl.hbcp.core.attributes.normalization.normalizers.NumericNormalizer;
import com.ibm.drl.hbcp.core.attributes.normalization.normalizers.TextValueNormalizer;
import com.ibm.drl.hbcp.core.attributes.normalization.normalizers.TimepointNormalizer;

import lombok.Data;

/**
 * Static class providing methods to normalize the value of any attribute.
 * Normalization turns the value into another value within a controlled range typically more convenient to process.
 * @author marting
 */
public class Normalizers {

    private final Properties props;

    private final Set<String> numericalAttributes;
    private final Set<String> presenceAttributes;
    private final Set<String> weekMonthAttributes;
    private final Set<String> mixedGenderAttributes;
    private final Set<String> categoricalAttributes;

    private final List<Pair<NormalizerCondition, Normalizer>> normalizers;

    public Normalizers(Properties props) {
        this.props = props;
        numericalAttributes = getAttributeIdsFromProperty(props, "prediction.attribtype.numerical");
        presenceAttributes = getAttributeIdsFromProperty(props, "prediction.attribtype.annotation.presence");
        weekMonthAttributes = getAttributeIdsFromProperty(props, "prediction.attribtype.week_month");
        mixedGenderAttributes = getAttributeIdsFromProperty(props, "prediction.attribtype.mixed.gender");
        categoricalAttributes = getAttributeIdsFromPropertyPrefix(props, "prediction.categories.");
        normalizers = getNormalizersPerCondition();
    }

    public Set<String> numericalAttributes() { return numericalAttributes; }
    public Set<String> presenceAttributes() { return presenceAttributes; }
    public Set<String> weekMonthAttributes() { return weekMonthAttributes; }
    public Set<String> mixedGenderAttributes() { return mixedGenderAttributes; }
    public Set<String> categoricalAttributes() { return categoricalAttributes; }
    
    /**
     * Normalize the value of several pairs belonging to the same attribute. Batch normalization is often
     * required because the normalization of one value can necessitate knowing the full distribution of values of its
     * attribute (for example when one wants to also apply some quantization).
     * @param pairs Attribute-value pairs of the same single attribute
     * @return the same pairs, with normalized value
     */
    public List<NormalizedAttributeValuePair> normalize(Collection<? extends ArmifiedAttributeValuePair> pairs) {
        if (!pairs.stream().allMatch(pair -> pair.getAttribute().equals(pairs.iterator().next().getAttribute())))
            throw new IllegalArgumentException("These attribute-value pairs should be from the same attribute.");
        if (pairs.isEmpty()) return Lists.newArrayList();
        // get (if necessary, build) the proper normalizer
        Optional<Normalizer> normalizer = getNormalizer(pairs);
        // normalize each value
        return pairs.stream()
                // TODO: there is some unchecked type black magic going on here, to confirm
                .map(armifiedAVP -> new NormalizedAttributeValuePair(armifiedAVP,
                        normalizer.isPresent() ? normalizer.get().getNormalizedValue(armifiedAVP) : armifiedAVP.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * Normalize the value of one pair.
     * @param pair Attribute-value pair
     * @return the same pair, with a normalized value
     */
    public NormalizedAttributeValuePair normalize(ArmifiedAttributeValuePair pair) {
        return normalize(Lists.newArrayList(pair)).get(0);
    }

    private Optional<Normalizer> getNormalizer(Collection<? extends ArmifiedAttributeValuePair> pairs) {
        final Attribute attribute = pairs.iterator().next().getAttribute();
        return normalizers.stream()
                .filter(conditionAndNormalizer -> conditionAndNormalizer.getLeft().test(attribute))
                .map(Pair::getRight)
                .findFirst();
    }

    private List<Pair<NormalizerCondition, Normalizer>> getNormalizersPerCondition() {
        List<Pair<NormalizerCondition, Normalizer>> res = new ArrayList<>();
        // follow-up normalizer
        TimepointNormalizer followupNormalizer = new TimepointNormalizer();
        for (String id : weekMonthAttributes) {
            res.add(Pair.of(new AttributeIdCondition(id), followupNormalizer));
        }
        // first deal with simple numeric values
        NumericNormalizer numNormalizer = new NumericNormalizer();
        for (String id : numericalAttributes) {
            res.add(Pair.of(new AttributeIdCondition(id), numNormalizer));
        }
        res.add(Pair.of(new AttributeTypeCondition(AttributeType.OUTCOME_VALUE), numNormalizer));
        // binary attributes
        BinaryAttributeNormalizer binaryNormalizer = new BinaryAttributeNormalizer();
        for (String id : presenceAttributes) {
            res.add(Pair.of(new AttributeIdCondition(id), binaryNormalizer));
        }
        res.add(Pair.of(new AttributeTypeCondition(AttributeType.INTERVENTION), binaryNormalizer));
        // mixed gender attributes
        MixedGenderNormalizer mixedGenderNormalizer = new MixedGenderNormalizer();
        for (String id : mixedGenderAttributes) {
            res.add(Pair.of(new AttributeIdCondition(id), mixedGenderNormalizer));
        }
        // last, consider the remaining attributes as categorical
        for (String id : categoricalAttributes) {
            res.add(Pair.of(new AttributeIdCondition(id), new TextValueNormalizer(props, id)));
        }
        return res;
    }

    /**
     * Parses a comma-separated list of values contained in the properties.
     * @param props The properties to look into
     * @param property The property name of the list to parse
     * @return The set of all the values of the requested properties
     */
    public static Set<String> getAttributeIdsFromProperty(Properties props, String property) {
        String allAttributeIds = props.getProperty(property);
        if (allAttributeIds != null) {
            return new HashSet<>(Arrays.asList(allAttributeIds.split(",")));
        } else return Sets.newHashSet();
    }

    private interface NormalizerCondition extends Predicate<Attribute> { }

    @Data
    private static class AttributeIdCondition implements NormalizerCondition {
        private final String attributeId;
        @Override
        public boolean test(Attribute attribute) { return attributeId.equals(attribute.getId()); }
    }

    @Data
    private static class AttributeTypeCondition implements NormalizerCondition {
        private final AttributeType attributeType;
        @Override
        public boolean test(Attribute attribute) { return attributeType == attribute.getType(); }
    }

    public static Set<String> getAttributeIdsFromPropertyPrefix(Properties props, String propertyPrefix) {
        return props.keySet().stream()
                .filter(propName -> propName.toString().startsWith(propertyPrefix))
                .map(propName -> propName.toString().substring(propName.toString().lastIndexOf(".") + 1))
                .collect(Collectors.toSet());
    }
}
