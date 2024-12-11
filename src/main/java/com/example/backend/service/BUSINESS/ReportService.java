package com.example.backend.service.BUSINESS;

import com.example.backend.dto.account.ExpenseDTO;
import com.example.backend.dto.account.ExpenseDetailDTO;
import com.example.backend.dto.pos.MonthlyIncomeDTO;
import com.example.backend.model.BUSINESS.*;
import com.example.backend.service.BANK.AccountService;
import com.example.backend.service.POS.PosService;
import com.example.backend.service.RedisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@Slf4j
@RequiredArgsConstructor
public class ReportService {

    private final PosService posService;
    private final AccountService accountService;
    private final JPAQueryFactory queryFactory;
    private final RedisService redisService;
    private final BusinessService businessService;

    @PersistenceContext
    private EntityManager em;

    @Value("${OPENAI_API_KEY}")
    private String openAiApiKey;

    private WebClient webClient;
    private static final String OPENAI_API_URL = "https://api.openai.com/v1";

    // 생성자에서 WebClient를 초기화합니다.
    @PostConstruct
    public void init() {
        log.info("API 키 확인: {}", openAiApiKey);

        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            log.error("API 키가 설정되지 않았습니다.");
            throw new IllegalStateException("API 키가 설정되지 않았습니다.");
        }

        this.webClient = WebClient.builder()
                .baseUrl(OPENAI_API_URL)
                .defaultHeader("Authorization", "Bearer " + openAiApiKey.trim())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Transactional
    public String getOrCreateReport(Long memberId, YearMonth month, String reportType) {


        BusinessRegistration businessRegistration = businessService.getBusinessIdByMemberID(memberId);

        if (businessRegistration == null) {
            throw new IllegalArgumentException("Member ID: " + memberId + "에 대한 BusinessRegistration이 존재하지 않습니다.");
        }

        LocalDate reportMonth = month.atDay(1);

        // 1. 리포트 조회
        Report existingReport = queryFactory.selectFrom(QReport.report)
                .where(
                        QReport.report.businessRegistration.businessRegistrationId.eq(businessRegistration.getBusinessRegistrationId()),
                        QReport.report.reportMonth.eq(reportMonth),
                        QReport.report.reportType.eq(reportType)
                )
                .fetchOne();

        // 2. 존재하면 JSON 반환
        if (existingReport != null) {
            return existingReport.getContent();
        }

        // 3. 리포트가 없으면 GPT API 호출 및 저장
        Map<String, Object> reportData = generateReportFromAPI(memberId, month, reportType);

        // API 호출이 성공적인지 검증
        if (reportData.containsKey("error")) {
            log.error("API 호출 오류로 리포트를 생성하지 못했습니다.{}", reportData.get("error"));
            throw new RuntimeException("리포트 생성 실패: " + reportData.get("error"));
        }

        saveReport(businessRegistration, reportMonth, reportType, reportData);


        try {
            String generatedContent = objectMapper.writeValueAsString(reportData); // JSON 직렬화
            log.info("생성된 리포트 반환: {}", generatedContent);
            return generatedContent;
        } catch (JsonProcessingException e) {
            log.error("JSON 직렬화 오류: {}", e.getMessage());
            throw new RuntimeException("JSON 직렬화 중 오류 발생", e);
        }


    }

    @Transactional
    public Map<String, Map<String, Object>> getAllReports(Long memberId, YearMonth month) {
        Map<String, Map<String, Object>> reports = new HashMap<>();

        // MARKET_REPORT 가져오기
        String marketReport = getOrCreateReport(memberId, month, "MARKET_REPORT");
        reports.put("MARKET_REPORT", parseJson(marketReport));

        // INDUSTRY_REPORT 가져오기
        String industryReport = getOrCreateReport(memberId, month, "INDUSTRY_REPORT");
        reports.put("INDUSTRY_REPORT", parseJson(industryReport));
        return reports;
    }

    private Map<String, Object> generateReportFromAPI(Long memberId, YearMonth month, String reportType) {
        if ("MARKET_REPORT".equals(reportType)) {
            return generateMarketReport(month);
        } else if ("INDUSTRY_REPORT".equals(reportType)) {
            return generateIndustryComparisonReport(memberId, month);
        }
        throw new IllegalArgumentException("Invalid report type: " + reportType);
    }


    private Map<String, Object> parseJson(String json) {
        try {
            // 입력 JSON 문자열 확인
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("JSON 데이터가 비어있습니다.");
            }
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 파싱 오류: " + e.getMessage(), e);
        }
    }

    public String getMarketIssueByMonth(YearMonth month) {
        QPrompt prompt = QPrompt.prompt;

        // "시장 이슈" 데이터 가져오기
        String marketIssues = queryFactory
                .select(prompt.contents)
                .from(prompt)
                .where(
                        prompt.month.eq(month),
                        prompt.type.eq("issue")
                )
                .fetchOne();

        return marketIssues != null ? marketIssues : "";
    }

    public String getTrendByMonth(YearMonth month) {
        QPrompt prompt = QPrompt.prompt;

        // "트렌드" 데이터 가져오기
        String trends = queryFactory
                .select(prompt.contents)
                .from(prompt)
                .where(
                        prompt.month.eq(month),
                        prompt.type.eq("trend")
                )
                .fetchOne();

        // "트렌드" 결과 합치기
        return trends != null ? trends : "";
    }


    @Transactional
    public void saveReport(BusinessRegistration businessRegistration, LocalDate reportMonth, String reportType, Map<String, Object> reportData) {
        try {
            String content = objectMapper.writeValueAsString(reportData);


            Report newReport = new Report();
            newReport.setBusinessRegistration(businessRegistration);
            newReport.setReportMonth(reportMonth);
            newReport.setReportType(reportType);
            newReport.setContent(content);

            em.persist(newReport);
//        em.flush();
        } catch (Exception e) {
            throw new RuntimeException("JSON 오류", e);
        }
    }

    //////////////////// 1. 경제 지표 활용 시장 동향 보고서 생성
    private final ObjectMapper objectMapper;

    public Map<String, Object> generateMarketReport(YearMonth month) {
        // 시장 이슈와 트렌드 데이터를 가져오기
        String market =  getMarketIssueByMonth(month);
        String trend = getTrendByMonth(month);

        // 실제 최신 데이터를 가져왔다고 가정한 예시입니다.
        String bsiIndex = "98";  // 실제 경제 지표 API로부터 가져올 수 있습니다.

        // 요청 바디 구성
        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o",
                "messages", List.of(
                        Map.of("role", "system", "content", String.format("당신은 %s 한국의 경제 뉴스를 기반으로 카페 운영자를 위한 시장 동향 보고서를 작성하는 AI입니다. " +
                                "실제 최신 뉴스와 공공 데이터(예: 한국은행, 기상청, 농림축산식품부, 통계청 등)를 기반으로 작성하세요. " +
                                "20대의 친근한 여성처럼 대답하세요.", month.toString())),
                        Map.of("role", "user", "content", String.format("""
                            다음 정보를 JSON 형식으로 정리해주세요:
                            
                            1. **month**: %s
                            2. **BSI_index**: %s
                            3. **BSI_description**: %s의 BSI 지수를 기준으로 한 기업들의 경기 전망 설명 (실제 데이터에 근거)
                            4. **market_issue**: %s
                            5. **trend**: %s
                            7. **recommendations**: 위 정보들을 바탕으로 카페 운영자를 위한 권장 사항 (실제 데이터를 기반으로 예측)
                            
                            모든 정보는 가능한 한 정확한 최신 자료에 기반하여 작성하며, 허구적인 데이터는 포함하지 마세요.
                            """,
                                month,
                                bsiIndex,
                                bsiIndex,
                                market,
                                trend
                                ))
                ),
                "functions", List.of(
                        Map.of(
                                "name", "generateMarketReport",
                                "description", "주요 재료 가격 변동 및 카페 음료, 디저트 소비 동향을 요약한 시장 동향 월간 리포트를 생성합니다. 모든 정보는 한국의 최신 뉴스와 공공 데이터를 기반으로 생성하세요.",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "month", Map.of("type", "integer", "description", "2024년 11월"),
                                                "BSI_index", Map.of("type", "integer", "description", "2024년 11월 15일 기준 최신 기업경기실사지수 (BSI)"),
                                                "BSI_description", Map.of("type", "string", "description", "오늘 기준 BSI 지수에 대한 설명"),
                                                "market_issue", Map.of("type", "string", "description", "주요 카페 재료의 가격 변동 (우유, 원두, 설탕 등)"),
                                                "trend", Map.of("type", "string", "description", "한국 내 최신 카페 소비 동향"),
                                                "recommendations", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "string"),
                                                        "description", "위 정보에 기반하여 카페 운영자를 위한 예측 및 권장 사항 목록"
                                                )
                                        ),
                                        "required", List.of("month", "BSI_index", "BSI_description", "market_issue", "trend", "recommendations")
                                )
                        )
                ),
                "function_call", Map.of("name", "generateMarketReport")
        );

        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // JSON 응답 파싱
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode argumentsNode = rootNode.path("choices").get(0).path("message").path("function_call").path("arguments");

            // argumentsNode가 문자열이므로 다시 JSON으로 파싱
            String argumentsJson = argumentsNode.asText();
            JsonNode parsedArguments = objectMapper.readTree(argumentsJson);

            // JsonNode를 Map으로 변환
            Map<String, Object> marketReport = objectMapper.convertValue(parsedArguments, Map.class);

            return marketReport;

        } catch (WebClientResponseException e) {
            System.err.println("API 호출 오류: " + e.getResponseBodyAsString());
            return Map.of("error", "API 호출 오류 발생");
        } catch (Exception e) {
            System.err.println("예상치 못한 오류 발생: " + e.getMessage());
            return Map.of("error", "예상치 못한 오류 발생");
        }
    }

    /////////////////////// 2. &#xB3D9;&#xC885; &#xC5C5;&#xACC4; &#xBE44;&#xAD50; &#xBD84;&#xC11D; &#xBCF4;&#xACE0;&#xC11C; &#xC0DD;&#xC131; (&#xC9C0;&#xC5ED; &#xAE30;&#xBC18;)
    public Map<String,Object> generateIndustryComparisonReport(Long memberId, YearMonth month) {

            Map<String, Object> monthlyIncome = posService.calculateAverageMonthlyMetrics(month);
            Map<String, Object> categoryExpense = accountService.getAccountHistoryByRegion(memberId, month);
            MonthlyIncomeDTO myIncome  = posService.getMonthlyIncomeSummary(memberId, month);
            ExpenseDTO myExpense = accountService.showSimpleExpense(memberId,month);


        String content = String.format("""
                    다음 데이터는 평균 카페 운영 관련 지출 및 매출 데이터입니다.
                    - 평균 매출 정보: %s
                    - 평균 지출 정보: %s
                    
                    다음 데이터는 나의 카페 운영 관련 지출 및 매출 데이터입니다.
                    - 평균 나의 매출 정보: %s
                    - 평균 나의 지출 정보: %s
                    """, monthlyIncome, categoryExpense, myIncome, myExpense
            );
        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o",
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "당신은 카페를 운영하는 사장님을 위한 시장 동향 보고서를 작성하는 AI입니다. " +
                                        "다음 데이터는 카페 운영 관련 지출 및 매출 데이터입니다: " + content +
                                        "이를 바탕으로 동종 업계와 비교하여 분석 결과를 JSON 형식으로 제공해주세요. " +
                                        "답변은 20대의 친근한 여성처럼 작성해주세요."
                        ),
                        Map.of("role", "user", "content",
                                """
                                다음 정보를 JSON 형식으로 정리하세요. 금액은 "약 ~ 만원" 형식으로 입력하며, 
                                입력된 금액이 소수점을 포함하거나 정수 형태인 경우 모두 1만 원 단위로 반올림하여 표현합니다.
                                   - 예: 5145000.00 → "약 515만원"
                                   - 예: 11814000 → "약 1181만원"
                                 :
                    
                                1. **average_sale**: "약 ~ 만원" 형식으로 된 주변 동종 업계 매출 평균을 입력합니다. (예: "약 1181만원")
                                2. **average_expense**: "약 ~ 만원" 형식으로 된 주변 동종 업계 지출 평균을 입력합니다. (예: "약 821만원")
                                3. **my_income**: "약 ~ 만원" 형식으로 된 나의 매출을 입력합니다. (예: "약 281만원")
                                4. **my_expense**: "약 ~ 만원" 형식으로 된 나의 지출을 입력합니다. (예: "약 181만원")
                                5. **sale_description**: 주변 업계와 비교한 매출 분석 결과를 서술합니다. 분석 결과는 다음 요소를 포함해야 합니다:
                                   - 주변 업계 대비 매출 비율 (예: "주변 카페들에 비해 10% 낮아요.")
                                   - 매출이 주로 발생하는 시간대 (예: "아침 시간대에 매출이 높아요.")
                                   - 카드와 현금 매출의 비율 차이 (예: "카드 거래가 대부분이에요.")
                                   - 추천 사항: 매출 시간이 적은 시간의 매출 증대를 위한 전략 및 실행 가능한 조언을 포함합니다 
                                6. **expense_description**: 주변 업계와 비교한 지출 분석 결과를 서술합니다. 분석 결과는 다음 요소를 포함해야 합니다:
                                   - 특정 지출 카테고리(예: 인건비, 공과금, 임대료 등)와 비교
                                   - 해당 카테고리의 평균 지출 금액 (예: "임대료는 평균 100만원이에요.")
                                   - 나의 지출이 평균 대비 몇 % 더 높은지 또는 낮은지 (예: "임대료가 15% 높아요.")
                                   - 추천 사항: 지출 중 평균 대비 높은 지출을 절감 및 효율화를 위한 실행 가능한 조언을 포함합니다 
                                """
                        )
                ),

        "functions", List.of(
                            Map.of(
                                    "name", "generateIndustryComparisonReport",
                                    "description", "지출과 매출에 대한 비교 분석 리포트를 생성합니다.",
                                    "parameters", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                "average_sale", Map.of("type", "string", "description", "약 ~ 만원 형식으로 된 매출 평균"),
                                                "average_expense", Map.of("type", "string", "description", "약 ~ 만원 형식으로 된 지출 평균"),
                                                "my_income", Map.of("type", "string", "description", "약 ~ 만원 형식으로 된 나의 매출"),
                                                "my_expense", Map.of("type", "string", "description", "약 ~ 만원 형식으로 된 나의 지출"),
                                                "sale_description", Map.of("type", "string", "description", "주변 카페와 비교한 카테고리 별 지출 분석 결과"),
                                                "expense_description", Map.of("type", "string", "description", "주변 카페와 비교한 시간과 매출 타입(카드/현금) 분석 결과")
                                            )
                                    ),
                                    "required", List.of("average_sale", "average_expense","my_income", "my_expense", "sale_description","expense_description")
                            )
                    ),
                    "function_call", Map.of("name", "generateIndustryComparisonReport")

            );


        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // JSON 응답 파싱
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode argumentsNode = rootNode.path("choices").get(0).path("message").path("function_call").path("arguments");

            // argumentsNode가 문자열이므로 다시 JSON으로 파싱
            String argumentsJson = argumentsNode.asText();
            JsonNode parsedArguments = objectMapper.readTree(argumentsJson);

            // JsonNode를 Map으로 변환
            Map<String, Object> marketReport = objectMapper.convertValue(parsedArguments, Map.class);

            return marketReport;

        } catch (WebClientResponseException e) {
            System.err.println("API 호출 오류: " + e.getResponseBodyAsString());
            return Map.of("error", "API 호출 오류 발생");
        } catch (Exception e) {
            System.err.println("예상치 못한 오류 발생: " + e.getMessage());
            return Map.of("error", "예상치 못한 오류 발생");
        }
    }

    @Transactional
    public boolean previousMonthReportChecker(Long memberId) {
        // 현재 날짜 기준 전 달 계산
        YearMonth currentMonth = YearMonth.now();
        YearMonth previousMonth = currentMonth.minusMonths(1);
        LocalDate previousReportMonth = previousMonth.atDay(1); // 전 달의 첫 번째 날

        // BusinessRegistration 조회
        BusinessRegistration businessRegistration = businessService.getBusinessIdByMemberID(memberId);


        if (businessRegistration == null) {
            throw new IllegalArgumentException("회원 ID: " + memberId + "에 해당하는 사업자 등록 정보가 존재하지 않습니다.");
        }

        // 리포트 타입 리스트 (MARKET_REPORT와 INDUSTRY_REPORT)
        List<String> reportTypes = List.of("MARKET_REPORT", "INDUSTRY_REPORT");

        // 각 리포트 타입에 대해 존재 여부 확인
        boolean allReportsExist = reportTypes.stream().allMatch(reportType ->
                queryFactory.selectFrom(QReport.report)
                        .where(
                                QReport.report.businessRegistration.businessRegistrationId.eq(businessRegistration.getBusinessRegistrationId()),
                                QReport.report.reportMonth.eq(previousReportMonth),
                                QReport.report.reportType.eq(reportType)
                        )
                        .fetchOne() != null
        );

        return allReportsExist;
    }



}
