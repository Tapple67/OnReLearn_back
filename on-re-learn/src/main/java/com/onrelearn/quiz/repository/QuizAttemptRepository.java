package com.onrelearn.quiz.repository;
import com.onrelearn.quiz.entity.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt,String> {
    @org.springframework.data.jpa.repository.Query("""
        select a.id as id, q.id as quizSetId, coalesce(q.title, m.title) as title,
               a.status as status, a.startedAt as startedAt
        from QuizAttempt a join a.quizSet q join q.material m join m.subject s
        where a.userId = :userId and s.grade.user.id = :userId
        order by coalesce(a.gradedAt, a.submittedAt, a.startedAt) desc, a.id desc
        """)
    java.util.List<RecentQuizAttempt> recentForUser(
        @org.springframework.data.repository.query.Param("userId") String userId,
        org.springframework.data.domain.Pageable page);

    interface RecentQuizAttempt {
        String getId();
        String getQuizSetId();
        String getTitle();
        com.onrelearn.quiz.model.QuizAttemptStatus getStatus();
        java.time.Instant getStartedAt();
    }

    @org.springframework.data.jpa.repository.Query("""
        select a.id as id, s.id as subjectId, s.name as subjectName,
               m.title as materialTitle, q.title as quizTitle,
               a.status as status, a.score as score, a.maxScore as maxScore,
               a.startedAt as startedAt, a.submittedAt as submittedAt,
               a.gradedAt as gradedAt
        from QuizAttempt a join a.quizSet q join q.material m join m.subject s
        where a.userId = :userId and s.grade.user.id = :userId and a.status in :statuses
        order by a.startedAt desc, a.id desc
        """)
    java.util.List<ProfileAttempt> profileAttempts(
        @org.springframework.data.repository.query.Param("userId") String userId,
        @org.springframework.data.repository.query.Param("statuses")
            java.util.List<com.onrelearn.quiz.model.QuizAttemptStatus> statuses);

    interface ProfileAttempt {
        String getId();
        String getSubjectId();
        String getSubjectName();
        String getMaterialTitle();
        String getQuizTitle();
        com.onrelearn.quiz.model.QuizAttemptStatus getStatus();
        Integer getScore();
        Integer getMaxScore();
        java.time.Instant getStartedAt();
        java.time.Instant getSubmittedAt();
        java.time.Instant getGradedAt();
    }
    @org.springframework.data.jpa.repository.Query("""
        select coalesce(a.gradedAt, a.submittedAt, a.startedAt)
        from QuizAttempt a
        where a.userId = :userId and a.status in :statuses
        """)
    java.util.List<java.time.Instant> completionTimesForUser(
        @org.springframework.data.repository.query.Param("userId") String userId,
        @org.springframework.data.repository.query.Param("statuses")
            java.util.List<com.onrelearn.quiz.model.QuizAttemptStatus> statuses);
    @org.springframework.data.jpa.repository.Query("""
        select a.quizSet.id as quizSetId, a.id as id, a.status as status,
               a.score as score, a.maxScore as maxScore, a.startedAt as startedAt,
               a.submittedAt as submittedAt
        from QuizAttempt a
        where a.userId = :userId and a.quizSet.material.id = :materialId
        order by a.startedAt desc, a.id desc
        """)
    java.util.List<Summary> summariesForMaterial(
        @org.springframework.data.repository.query.Param("userId") String userId,
        @org.springframework.data.repository.query.Param("materialId") String materialId);

    interface Summary {
        String getQuizSetId();
        String getId();
        com.onrelearn.quiz.model.QuizAttemptStatus getStatus();
        Integer getScore();
        Integer getMaxScore();
        java.time.Instant getStartedAt();
        java.time.Instant getSubmittedAt();
    }

    @org.springframework.data.jpa.repository.Query("""
        select a.quizSet.material.subject.id from QuizAttempt a
        where a.id=:id and a.userId=:userId and a.quizSet.material.subject.grade.user.id=:userId
        """)
    java.util.Optional<String> findOwnedSubjectId(
        @org.springframework.data.repository.query.Param("id") String id,
        @org.springframework.data.repository.query.Param("userId") String userId);

    java.util.List<QuizAttempt> findByUserIdAndQuizSet_IdOrderByStartedAtDesc(String userId,String quizSetId);
    java.util.Optional<QuizAttempt> findByIdAndUserIdAndQuizSet_Material_Subject_Grade_User_Id(String id,String userId,String materialOwnerId);
    java.util.Optional<QuizAttempt> findByIdAndUserId(String id,String userId);
}
