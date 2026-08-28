package io.spring.infrastructure;

// Step 1.7 / B16: proves the MyBatis mapper/read-service layer (Step 1.3's / B9 work) actually
// produces correct results against a real PostgreSQL server, not just SQLite. Step 1.3 only
// smoke-tested a trivial `/tags` query; this test boots the full Spring context against a
// Testcontainers-managed Postgres 14 (matching the version prior steps validated), lets Flyway
// run V1__create_tables.sql for real (not disabled), and drives writes + pagination + tag
// filtering + favorites + comments through the actual repository/query-service beans so any
// dialect mismatch (e.g. a MySQL/SQLite-only SQL construct that Postgres rejects, or a query
// that runs but returns the wrong rows) shows up as a real assertion failure here.
//
// Lives in a separate Gradle task ("integrationTest", see build.gradle) because it needs local
// Docker; it is excluded from the default `test` task so the fast unit suite stays Docker-free.

import static org.assertj.core.api.Assertions.assertThat;

import io.spring.application.ArticleQueryService;
import io.spring.application.CommentQueryService;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.CommentData;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.comment.Comment;
import io.spring.core.comment.CommentRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PostgresIntegrationTest {

  // Postgres 14: same major version Step 1.3 already smoke-tested against, kept consistent here
  // so this test is validating the schema/queries, not a version jump.
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:14")
          .withDatabaseName("realworld_it")
          .withUsername("realworld")
          .withPassword("realworld");

  // Overrides application-test.properties' SQLite in-memory datasource with the real container.
  // @DynamicPropertySource values take precedence over both application.properties and
  // application-{profile}.properties, so "test" can stay active (for its jwt.secret) while the
  // datasource itself points at Postgres. Flyway is not given its own spring.flyway.* url, so
  // Spring Boot's FlywayAutoConfiguration reuses this same primary datasource and runs the real
  // V1__create_tables.sql migration against Postgres -- it is never disabled for this test.
  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
  }

  @Autowired private UserRepository userRepository;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private ArticleQueryService articleQueryService;
  @Autowired private ArticleFavoriteRepository articleFavoriteRepository;
  @Autowired private CommentRepository commentRepository;
  @Autowired private CommentQueryService commentQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User author;

  // Truncate everything before each test (rather than after) so a prior test that failed
  // mid-way never leaves state behind for the next one -- keeps the suite order-independent
  // (Hard rule: reset state between test methods). None of these tables have FK constraints
  // declared in V1__create_tables.sql, so a single multi-table TRUNCATE is safe regardless of
  // ordering.
  @BeforeEach
  void resetDatabaseAndCreateAuthor() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE article_favorites, article_tags, comments, tags, articles, follows, users");
    author = new User("author@realworld.test", "author", "password", "bio", "image.png");
    userRepository.save(author);
  }

  // (a) Create a user, prove it round-trips through real Postgres with every field intact.
  @Test
  void should_create_and_read_back_a_user() {
    User fetchedById = userRepository.findById(author.getId()).orElseThrow(AssertionError::new);
    assertThat(fetchedById.getUsername()).isEqualTo("author");
    assertThat(fetchedById.getEmail()).isEqualTo("author@realworld.test");
    assertThat(fetchedById.getBio()).isEqualTo("bio");
    assertThat(fetchedById.getImage()).isEqualTo("image.png");

    User fetchedByUsername =
        userRepository.findByUsername("author").orElseThrow(AssertionError::new);
    assertThat(fetchedByUsername.getId()).isEqualTo(author.getId());
  }

  // (b) Create an article with tags, prove the article + its tag list + author profile all read
  // back correctly from Postgres (exercises the article/article_tags/tags joins in
  // ArticleReadService.xml).
  @Test
  void should_create_article_with_tags_and_read_it_back() {
    Article article =
        new Article(
            "Real Postgres Title",
            "a real description",
            "a real body",
            Arrays.asList("java", "postgres"),
            author.getId());
    articleRepository.save(article);

    ArticleData fetched =
        articleQueryService.findBySlug(article.getSlug(), author).orElseThrow(AssertionError::new);

    assertThat(fetched.getTitle()).isEqualTo("Real Postgres Title");
    assertThat(fetched.getDescription()).isEqualTo("a real description");
    assertThat(fetched.getBody()).isEqualTo("a real body");
    assertThat(new HashSet<>(fetched.getTagList()))
        .isEqualTo(new HashSet<>(Arrays.asList("java", "postgres")));
    assertThat(fetched.getProfileData().getUsername()).isEqualTo("author");
    assertThat(fetched.getFavoritesCount()).isEqualTo(0);
    assertThat(fetched.isFavorited()).isFalse();
  }

  // (c) List articles with pagination: 5 articles, page size 2, assert exact page contents (not
  // just non-empty) plus the total count field, across three offsets including a trailing
  // partial page and an out-of-range page. This is what actually exercises ArticleReadService's
  // `limit #{page.offset}, #{page.limit}` clause against Postgres.
  @Test
  void should_paginate_article_list_with_correct_items_and_total_count() {
    List<Article> created = createArticlesWithStaggeredTimestamps(5);
    // createArticlesWithStaggeredTimestamps creates oldest-first; the API returns newest-first.
    List<String> newestFirstIds =
        created.stream()
            .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
            .map(Article::getId)
            .collect(Collectors.toList());

    ArticleDataList firstPage =
        articleQueryService.findRecentArticles(null, null, null, new Page(0, 2), author);
    assertThat(firstPage.getCount()).isEqualTo(5);
    assertThat(firstPage.getArticleDatas()).hasSize(2);
    assertThat(idsOf(firstPage)).isEqualTo(newestFirstIds.subList(0, 2));

    ArticleDataList secondPage =
        articleQueryService.findRecentArticles(null, null, null, new Page(2, 2), author);
    assertThat(secondPage.getCount()).isEqualTo(5);
    assertThat(secondPage.getArticleDatas()).hasSize(2);
    assertThat(idsOf(secondPage)).isEqualTo(newestFirstIds.subList(2, 4));

    ArticleDataList trailingPartialPage =
        articleQueryService.findRecentArticles(null, null, null, new Page(4, 2), author);
    assertThat(trailingPartialPage.getCount()).isEqualTo(5);
    assertThat(trailingPartialPage.getArticleDatas()).hasSize(1);
    assertThat(idsOf(trailingPartialPage)).isEqualTo(newestFirstIds.subList(4, 5));

    ArticleDataList beyondEnd =
        articleQueryService.findRecentArticles(null, null, null, new Page(10, 2), author);
    assertThat(beyondEnd.getCount()).isEqualTo(5);
    assertThat(beyondEnd.getArticleDatas()).isEmpty();
  }

  // (d) Filter articles by tag: only articles actually tagged with the requested tag come back.
  @Test
  void should_filter_articles_by_tag() {
    Article javaAndSpring =
        new Article(
            "java and spring", "desc", "body", Arrays.asList("java", "spring"), author.getId());
    Article springOnly =
        new Article(
            "spring only", "desc", "body", Collections.singletonList("spring"), author.getId());
    Article pythonOnly =
        new Article(
            "python only", "desc", "body", Collections.singletonList("python"), author.getId());
    articleRepository.save(javaAndSpring);
    articleRepository.save(springOnly);
    articleRepository.save(pythonOnly);

    ArticleDataList springArticles =
        articleQueryService.findRecentArticles("spring", null, null, new Page(), author);
    assertThat(springArticles.getCount()).isEqualTo(2);
    assertThat(idsOf(springArticles))
        .containsExactlyInAnyOrder(javaAndSpring.getId(), springOnly.getId());
    assertThat(idsOf(springArticles)).doesNotContain(pythonOnly.getId());

    ArticleDataList noMatch =
        articleQueryService.findRecentArticles("rust", null, null, new Page(), author);
    assertThat(noMatch.getCount()).isEqualTo(0);
    assertThat(noMatch.getArticleDatas()).isEmpty();
  }

  // (e) Favourite the article: favorited flag + favorites count are correct for both the
  // favoriting user and a bystander, and unfavoriting brings the count back down.
  @Test
  void should_favorite_article_and_reflect_state_and_count() {
    Article article =
        new Article(
            "favorite me", "desc", "body", Collections.singletonList("java"), author.getId());
    articleRepository.save(article);
    User fan = new User("fan@realworld.test", "fan", "password", "", "");
    userRepository.save(fan);

    articleFavoriteRepository.save(new ArticleFavorite(article.getId(), fan.getId()));

    ArticleData asSeenByFan =
        articleQueryService.findById(article.getId(), fan).orElseThrow(AssertionError::new);
    assertThat(asSeenByFan.isFavorited()).isTrue();
    assertThat(asSeenByFan.getFavoritesCount()).isEqualTo(1);

    ArticleData asSeenByAuthor =
        articleQueryService.findById(article.getId(), author).orElseThrow(AssertionError::new);
    assertThat(asSeenByAuthor.isFavorited()).isFalse();
    assertThat(asSeenByAuthor.getFavoritesCount()).isEqualTo(1);

    assertThat(articleFavoriteRepository.find(article.getId(), fan.getId())).isPresent();

    articleFavoriteRepository.remove(new ArticleFavorite(article.getId(), fan.getId()));

    ArticleData afterUnfavorite =
        articleQueryService.findById(article.getId(), fan).orElseThrow(AssertionError::new);
    assertThat(afterUnfavorite.isFavorited()).isFalse();
    assertThat(afterUnfavorite.getFavoritesCount()).isEqualTo(0);
    assertThat(articleFavoriteRepository.find(article.getId(), fan.getId())).isEmpty();
  }

  // (f) Comment on the article: content and author come back correctly on read.
  @Test
  void should_comment_on_article_and_read_back_content_and_author() {
    Article article =
        new Article(
            "commentable", "desc", "body", Collections.singletonList("java"), author.getId());
    articleRepository.save(article);
    User commenter = new User("commenter@realworld.test", "commenter", "password", "", "");
    userRepository.save(commenter);

    Comment comment = new Comment("a real comment body", commenter.getId(), article.getId());
    commentRepository.save(comment);

    List<CommentData> comments = commentQueryService.findByArticleId(article.getId(), author);
    assertThat(comments).hasSize(1);
    CommentData commentData = comments.get(0);
    assertThat(commentData.getBody()).isEqualTo("a real comment body");
    assertThat(commentData.getProfileData().getUsername()).isEqualTo("commenter");

    Optional<CommentData> fetchedById = commentQueryService.findById(comment.getId(), author);
    assertThat(fetchedById).isPresent();
    assertThat(fetchedById.get().getBody()).isEqualTo("a real comment body");
  }

  private List<Article> createArticlesWithStaggeredTimestamps(int count) {
    DateTime base = new DateTime();
    List<Article> articles = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Article article =
          new Article(
              "article " + i,
              "desc " + i,
              "body " + i,
              Collections.singletonList("java"),
              author.getId(),
              base.plusMinutes(i));
      articleRepository.save(article);
      articles.add(article);
    }
    return articles;
  }

  private static List<String> idsOf(ArticleDataList list) {
    return list.getArticleDatas().stream().map(ArticleData::getId).collect(Collectors.toList());
  }
}
