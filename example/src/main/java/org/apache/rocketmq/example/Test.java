package org.apache.rocketmq.example;

import java.util.*;

/**
 * Created by l_yy on 2017/6/29.
 */
public class Test {


    public static void main(String[] args) {


        String input  =   "aabaca";


        System.out.print(sub(input));






    }



    public static String sub(String input) {

        HashMap<Character, Integer> char2StartMap = new HashMap<>();
        HashMap<Character, Integer> char2EndMap = new HashMap<>();




        TreeMap<Integer, Character> treeMap = new TreeMap<>(new KeyComparator());


        for (int i = 0; i < input.length(); i++) {
            Integer startIndex =   char2StartMap.get(input.charAt(i));
            if (startIndex == null) {
                char2StartMap.put(input.charAt(i), i);
            } else {
                char2EndMap.put(input.charAt(i), i);
                int between = char2EndMap.get(input.charAt(i)) - char2StartMap.get(input.charAt(i));
                treeMap.put(between, input.charAt(i));
            }
        }


        if (char2EndMap.size() == 0) {
            return input.charAt(0) + "";
        }







        for (Map.Entry<Integer, Character> entry : treeMap.entrySet()) {

            int start = char2StartMap.get(entry.getValue());
            int end = char2EndMap.get(entry.getValue());
            boolean flag = true;

            for (int i= start, j=end; i==j; i++, end--) {
                if (input.charAt(i) != input.charAt(j)) {
                    flag = false;
                    break;
                }
            }

            if (flag) {
                return input.substring(start, end + 1);
            }
        }
        return null;
    }


    static class KeyComparator implements Comparator<Integer> {

        @Override
        public int compare(Integer o1, Integer o2) {

            if (o1 >= o2) {
                return -1;
            } else {
                return 1;
            }
        }
    }


}
