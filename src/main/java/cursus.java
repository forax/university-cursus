import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

class cursus {

  enum Semester {
    S1, S2, S3, S4, S5, S6, S7, S8, S9
  }
  record Concept(String name) {
    Concept {
      Objects.requireNonNull(name);
    }

    @Override
    public String toString() {
      return name;
    }
  }
  record Course(String title, Semester semester, List<Concept> newConcepts, List<Concept> dependencies) {
    Course {
      Objects.requireNonNull(title);
      Objects.requireNonNull(semester, "semester is null for " + title);
      Objects.requireNonNull(newConcepts, "newConcept is null for " + title);
      Objects.requireNonNull(dependencies, "dependencies is null for " + title);
    }
  }


  static void checkNewConceptsAreOnlyDeclaredOnce(List<Course> courses) {
    record ConceptPair(Course course, Concept concept) {}
    var conceptListMap = courses.stream()
        .flatMap(course -> course.newConcepts.stream().map(concept -> new ConceptPair(course, concept)))
        .collect(groupingBy(ConceptPair::concept, mapping(ConceptPair::course, toList())));
    boolean valid = true;
    for (var entry : conceptListMap.entrySet()) {
      var concept = entry.getKey();
      var courseList = entry.getValue();

      // remove re-exported concepts (concept in the new list and in the dependency list)
      var courseListNoReExport = courseList.stream()
          .filter(course -> !course.dependencies.contains(concept))
          .toList();

      if (courseListNoReExport.size() > 1) {
        System.err.println("concept " + concept + " is declared as new by " + courseList);
      }
    }
  }

  static void checkConceptDependencyAllExist(List<Course> courses) {
    var concepts = courses.stream()
        .flatMap(course -> course.newConcepts.stream())
        .collect(toSet());
    for(var course: courses) {
      for(var dependency: course.dependencies) {
        if (!concepts.contains(dependency)) {
          System.err.println("no concept " + dependency + " defined for " + course);
        }
      }
    }
  }

  static Map<Semester, List<Course>> computeCoursePerSemesterMap(List<Course> courses) {
    return courses.stream()
        .collect(groupingBy(Course::semester, () -> new EnumMap<>(Semester.class), toList()));
  }

  static Map<Semester, Map<Concept, List<Course>>> computeConceptPerSemesterMap(Map<Semester, List<Course>> coursePerSemesterMap) {
    // for all concepts of a semester, find the corresponding courses
    var map = new EnumMap<Semester, Map<Concept, List<Course>>>(Semester.class);
    for(var entry: coursePerSemesterMap.entrySet()) {
      var semester = entry.getKey();
      var courseList = entry.getValue();

      var conceptMap = new HashMap<Concept, List<Course>>();
      for(var course: courseList) {
        for(var newConcept: course.newConcepts) {
          conceptMap.computeIfAbsent(newConcept, __ -> new ArrayList<>()).add(course);
        }
        /*for(var newConcept: course.dependencies) {
          conceptMap.computeIfAbsent(newConcept, __ -> new ArrayList<>()).add(course);
        }*/
      }
      map.put(semester, conceptMap);
    }
    return map;
  }

  private static final List<Semester> ALL_SEMESTERS = List.of(Semester.values());

  static List<Semester> previousSemesters(Semester semester) {
    return ALL_SEMESTERS.subList(0, semester.ordinal()).reversed();
  }

  static Map<Course, Map<Course, Set<Concept>>> computeCourseDependencyWithConcepts(Map<Semester, List<Course>> coursePerSemesterMap, Map<Semester, Map<Concept, List<Course>>> conceptPerSemesterMap) {
    var courseDependencyMap = new LinkedHashMap<Course, Map<Course, Set<Concept>>>();
    for(var entry: coursePerSemesterMap.entrySet()) {
      var semester = entry.getKey();
      var courseList = entry.getValue();
      for(var course: courseList) {
        var dependencyCourses = new LinkedHashMap<Course, Set<Concept>>();
        for(var concept: course.dependencies) {
          // look for the concept in the previous semesters
          var foundDependency = false;
          loop: for(var previousSemester: previousSemesters(semester)) {
            var previousCourses = conceptPerSemesterMap.get(previousSemester).getOrDefault(concept, List.of());
            if (!previousCourses.isEmpty()) {
              for(var previous: previousCourses) {
                dependencyCourses.computeIfAbsent(previous, __ -> new HashSet<>()).add(concept);
              }
              foundDependency = true;
              break loop;
            }
          }
          if (!foundDependency) {
            // look for the concept in the current semester
            for (var currentCourse : courseList) {
              if (currentCourse.newConcepts().contains(concept)) { // quite awful algorithmically
                dependencyCourses.computeIfAbsent(currentCourse, __ -> new HashSet<>()).add(concept);
              }
            }
          }
        }
        courseDependencyMap.put(course, dependencyCourses);
      }
    }
    return courseDependencyMap;
  }

  static Map<Course, Integer> idMap(List<Course> courses) {
    var counter = new Object() { int count; };
    return courses.stream()
        .collect(toMap(course -> course, __ -> counter.count++));
  }

  static String id(Course course, Map<Course, Integer> idMap) {
    return "id" + idMap.get(course);
  }

  static String generateMermaidFlowchart(List<Course> courses, Map<Semester, List<Course>> coursePerSemesterMap, Map<Course, Map<Course, Set<Concept>>> courseDependencyWithConceptMap) {
    var builder = new StringBuilder();
    builder.append("flowchart LR\n");

    var idMap = idMap(courses);

    // generate subgraph with courses in it
    for (var entry : coursePerSemesterMap.entrySet()) {
      var semester = entry.getKey();
      var courseList = entry.getValue();

      builder.append("subgraph " + semester + "\n");
      for(var course: courseList) {
        builder.append("  " + id(course, idMap) + "(" + course.title + ")\n");
      }
      builder.append("end " + semester + "\n");
    }
    builder.append("\n");

    // generate dependencies
    for(var entry: courseDependencyWithConceptMap.entrySet()) {
      var source = entry.getKey();
      for(var destinationEntry : entry.getValue().entrySet()) {
        var destination = destinationEntry.getKey();
        var concepts = destinationEntry.getValue();

        var conceptNames =concepts.stream().map(Concept::name).collect(Collectors.joining(","));
        builder.append(id(destination, idMap) + " --" + conceptNames + "--> " + id(source, idMap) + "\n");
      }
    }
    return builder.toString();
  }



  static final class ConceptCache {
    private final HashMap<String, Concept> cache = new HashMap<>();

    Concept concept(String name) {
      return cache.computeIfAbsent(name, Concept::new);
    }
  }

  static List<Course> parseXML(Path path) throws IOException, ParserConfigurationException, SAXException {
    var saxParserFactory = SAXParserFactory.newDefaultInstance();
    saxParserFactory.setNamespaceAware(true);
    var parser = saxParserFactory.newSAXParser();
    var courses = new ArrayList<Course>();
    var conceptCache = new ConceptCache();
    try (var input = Files.newInputStream(path)) {
      parser.parse(input, new DefaultHandler() {
        private StringBuilder builder = new StringBuilder();
        private String title;
        private Semester semester;
        private List<Concept> newConcepts;
        private List<Concept> dependencies;

        private List<Concept> toConceptList(String text) {
          if (text.isEmpty()) {
            return List.of();
          }
          return Arrays.stream(text.split(","))
              .map(String::strip)
              .map(conceptCache::concept)
              .toList();
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
          builder.append(ch, start, length);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
          switch (localName) {
            case "course" -> title = attributes.getValue("title");
            case "cursus", "semester", "new-concept", "dependency-concept" -> {}
            default -> throw new SAXException("unknown element " + localName);
          }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
          switch (localName) {
            case "semester" -> semester = Semester.valueOf(builder.toString().strip());
            case "new-concept" -> newConcepts = toConceptList(builder.toString().strip());
            case "dependency-concept" -> dependencies = toConceptList(builder.toString().strip());
            case "course" -> {
              courses.add(new Course(title, semester, newConcepts, dependencies));
              title = null;
              semester = null;
              newConcepts = null;
              dependencies = null;
            }
            case "cursus" -> {}
            default -> throw new SAXException("unknown element " + localName);
          }
          builder.setLength(0);
        }
      });
    }
    return courses;
  }

  private static String titleAndSemester(Course course) {
    return course.title + "[" + course.semester + "]";
  }

  public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException {
    var courses = parseXML(Path.of("cursus.xml"));
    checkNewConceptsAreOnlyDeclaredOnce(courses);
    checkConceptDependencyAllExist(courses);

    var coursePerSemesterMap = computeCoursePerSemesterMap(courses);
    var conceptPerSemesterMap = computeConceptPerSemesterMap(coursePerSemesterMap);
    var courseDependencyWithConceptMap = computeCourseDependencyWithConcepts(coursePerSemesterMap, conceptPerSemesterMap);

//    for(var entry: courseDependencyWithConceptMap.entrySet()) {
//      var source = entry.getKey();
//      for(var destinationEntry : entry.getValue().entrySet()) {
//        var destination = destinationEntry.getKey();
//        var concepts = destinationEntry.getValue();
//        System.out.println(titleAndSemester(source) + " --> " + titleAndSemester(destination) + " with " + concepts);
//      }
//    }

    var mermaidFiled = Path.of("cursus.mmd");
    var mermaidText = generateMermaidFlowchart(courses, coursePerSemesterMap, courseDependencyWithConceptMap);
    Files.writeString(mermaidFiled, mermaidText);
    System.out.println(mermaidFiled + " generated");
  }
}
